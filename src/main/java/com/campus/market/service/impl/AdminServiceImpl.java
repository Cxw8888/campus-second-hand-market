package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.enums.AuditOperationType;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.common.result.PageResult;
import com.campus.market.dto.admin.AdminOrderQuery;
import com.campus.market.dto.admin.AdminProductQuery;
import com.campus.market.dto.admin.AdminUserQuery;
import com.campus.market.dto.admin.AuditLogQuery;
import com.campus.market.dto.admin.OrderUnfreezeRequest;
import com.campus.market.dto.admin.ProductAuditRequest;
import com.campus.market.entity.AuditLog;
import com.campus.market.entity.Category;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.User;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.AuditLogMapper;
import com.campus.market.mapper.CategoryMapper;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.mapper.UserMapper;
import com.campus.market.service.AdminAuditService;
import com.campus.market.service.AdminService;
import com.campus.market.service.NotificationSender;
import com.campus.market.service.ProductCacheService;
import com.campus.market.service.StockService;
import com.campus.market.service.TokenVersionService;
import com.campus.market.util.TransactionHelper;
import com.campus.market.vo.AdminUserVO;
import com.campus.market.vo.AuditLogVO;
import com.campus.market.vo.OrderVO;
import com.campus.market.vo.ProductListVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端服务实现。
 *
 * <p>封禁事务边界（批次 6.0.3 · B1/B2 起）：①status=1 ②<b>冻结</b>未完成订单（0/1/2/6→5，
 * <b>不回补库存</b>）③下架可售商品（status IN (1,2) → 0）④写审计日志 —— 四步同一事务；
 * ⑤user:token:version+1 在事务提交后执行（失败重试 3 次）。
 * 拦截器在版本比对失败时兜底查询 user:status:{userId}，双保险确保封禁立即生效。</p>
 *
 * <p><b>为什么封禁不回补库存</b>：冻结只是"交易暂停"，真正终止交易的是 5→4（管理员解冻转取消）。
 * 修前"封禁时回补 + 解冻 CANCEL 时又回补"会让同一订单补两遍（库存 1 的商品被刷成 2），
 * 反复"封禁→解冻"可无限刷库存（自审报告 B1）。现在回补只在 5→4 发生一次，
 * 并由 {@code StockService.restoreOnce} 的 {@code order:restored:{orderId}} 凭证兜住重复调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private static final int PRODUCT_ON_SALE = 1;
    private static final int PRODUCT_SOLD_OUT = 2;
    private static final int PRODUCT_OFF_SHELF = 0;
    private static final int PRODUCT_PENDING_AUDIT = 3;

    private static final int USER_NORMAL = 0;
    private static final int USER_BANNED = 1;

    private static final int TYPE_ORDER = 1;
    private static final int TYPE_AUDIT = 2;
    private static final int BIZ_TYPE_ORDER = 1;
    private static final int BIZ_TYPE_PRODUCT = 2;

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    private final CategoryMapper categoryMapper;
    private final AuditLogMapper auditLogMapper;
    private final StockService stockService;
    private final ProductCacheService productCacheService;
    private final AdminAuditService adminAuditService;
    private final TokenVersionService tokenVersionService;
    private final NotificationSender notificationSender;

    // ================================================================ 商品

    @Override
    public PageResult<ProductListVO> listAuditProducts(AdminProductQuery query) {
        Page<Product> page = new Page<>(query.current(), query.pageSize());
        IPage<Product> result = productMapper.selectPage(page, Wrappers.<Product>lambdaQuery()
                .eq(query.getStatus() != null, Product::getStatus, query.getStatus())
                .eq(query.getUserId() != null, Product::getUserId, query.getUserId())
                // 参数化 LIKE，严禁字符串拼接
                .like(query.getKeyword() != null && !query.getKeyword().isBlank(), Product::getTitle, query.getKeyword())
                .orderByAsc(Product::getCreateTime));
        Map<Long, String> categoryNames = loadCategoryNames(result.getRecords());
        Map<Long, User> sellers = loadSellers(result.getRecords());
        return PageResult.of(result, product -> toProductListVO(product, categoryNames, sellers));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditProduct(Long id, ProductAuditRequest request) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw BusinessException.productNotAvailable();
        }
        boolean pass = Boolean.TRUE.equals(request.getPass());
        int targetStatus = pass ? PRODUCT_ON_SALE : PRODUCT_OFF_SHELF;
        int rows = productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .set(Product::getStatus, targetStatus)
                .eq(Product::getId, id)
                .eq(Product::getStatus, PRODUCT_PENDING_AUDIT));
        if (rows == 0) {
            throw BusinessException.statusNotAllowed();
        }
        // 写路径缓存规范：审核状态流转 3→1 / 3→0 后必须删除商品详情缓存
        productCacheService.evictDetail(id);
        // ④ 审计日志（与业务同一事务，失败降级）
        adminAuditService.record(pass ? AuditOperationType.APPROVE_PRODUCT : AuditOperationType.REJECT_PRODUCT,
                "PRODUCT", id, "审核结果=" + (pass ? "通过" : "不通过") + ", 原因=" + request.getReason());
        // 审核结果通知发布者
        notificationSender.sendAsync(product.getUserId(), TYPE_AUDIT, BIZ_TYPE_PRODUCT, id,
                pass ? "您的商品「" + product.getTitle() + "」已审核通过并上架"
                        : "您的商品「" + product.getTitle() + "」审核未通过：" + request.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceOffline(Long id, String reason) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw BusinessException.productNotAvailable();
        }
        productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .set(Product::getStatus, PRODUCT_OFF_SHELF)
                .eq(Product::getId, id));
        // 写路径缓存规范：强制下架后必须删除商品详情缓存
        productCacheService.evictDetail(id);
        adminAuditService.record(AuditOperationType.FORCE_OFFLINE, "PRODUCT", id, "强制下架原因=" + reason);
        notificationSender.sendAsync(product.getUserId(), TYPE_AUDIT, BIZ_TYPE_PRODUCT, id,
                "您的商品「" + product.getTitle() + "」已被管理员强制下架，原因：" + reason);
    }

    // ================================================================ 用户

    @Override
    public PageResult<AdminUserVO> listUsers(AdminUserQuery query) {
        Page<User> page = new Page<>(query.current(), query.pageSize());
        IPage<User> result = userMapper.selectPage(page, Wrappers.<User>lambdaQuery()
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), wrapper -> wrapper
                        .like(User::getUsername, query.getKeyword())
                        .or().like(User::getNickname, query.getKeyword())
                        .or().like(User::getEmail, query.getKeyword()))
                .orderByDesc(User::getCreateTime));
        return PageResult.of(result, this::toAdminUserVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void banUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户不存在");
        }
        if (Integer.valueOf(USER_BANNED).equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
        }

        // ① 用户 status=1
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .set(User::getStatus, USER_BANNED)
                .eq(User::getId, userId));

        // ② 先冻结未完成订单（0/1/2/6→5）
        //    【批次 6.0.3 · B1】冻结【不再回补库存】：冻结只是"交易暂停"，
        //    真正终止交易的是 5→4（管理员解冻转取消），回补只在那里做一次。
        //    修前封禁时就回补 + 解冻 CANCEL 又回补 = 同一订单补两遍（库存 1 的商品被刷成 2），
        //    而且反复"封禁→解冻"可无限刷库存。
        int frozen = orderMapper.freezeByUser(userId);

        // ③ 再下架其可售商品（在售 1 + 售罄 2）
        //    【批次 6.0.3 · B2】必须覆盖【售罄(2)】：售罄商品同样是"还能被下单前先占住"的资产，
        //    修前只下架 status=1，售罄商品逃过下架；配合封禁回补（已按 B1 移除）还会被
        //    restoreStock 的 CASE 翻回在售，于是被封禁卖家的商品仍可被下单 —— 封禁形同虚设。
        //    顺序也调整为"先冻结订单、再下架商品"（与审计文案/文档一致）。
        List<Product> sellableProducts = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .select(Product::getId)
                .eq(Product::getUserId, userId)
                .in(Product::getStatus, PRODUCT_ON_SALE, PRODUCT_SOLD_OUT));
        int offShelf = productMapper.offShelfByUser(userId);
        // 写路径缓存规范：下架商品后批量失效详情缓存
        productCacheService.evictDetails(sellableProducts.stream().map(Product::getId).collect(Collectors.toSet()));

        // ④ 审计日志（同一事务）
        adminAuditService.record(AuditOperationType.BAN_USER, "USER", userId,
                "封禁用户, 下架可售商品数=" + offShelf + ", 冻结订单数=" + frozen + "（冻结不回补库存）");

        // ⑤ 事务提交后 version+1（失败重试 3 次、指数退避；拦截器有 user:status 兜底）
        tokenVersionService.increaseVersionAfterCommit(userId);
        // 【批次 6.0.4 · M4】状态缓存同样移到提交后写：修前在事务内写 user:status:{userId}，
        //    封禁事务一旦回滚 → DB 里用户正常、缓存却标记封禁（且当时无 TTL）→ 该用户被永久 401。
        //    现在回滚时根本不会写；再配合 TokenVersionServiceImpl 的 1 天 TTL 与
        //    拦截器"缓存说封禁时以库为准"，残留缓存最坏只影响一次请求且会自愈。
        TransactionHelper.runAfterCommit(() -> tokenVersionService.cacheUserStatus(userId, USER_BANNED));

        // 通知被封禁用户：相关订单已冻结，需线下处理
        if (frozen > 0) {
            notificationSender.sendAsync(userId, TYPE_ORDER, BIZ_TYPE_ORDER, 0L,
                    "您的账号已被封禁，名下 " + frozen + " 笔未完成订单已冻结，请联系管理员处理");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbanUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户不存在");
        }
        // 解封后订单保持 status=5 待线下处理，严禁自动恢复原状态
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .set(User::getStatus, USER_NORMAL)
                .eq(User::getId, userId));
        adminAuditService.record(AuditOperationType.UNBAN_USER, "USER", userId, "解封用户");
        tokenVersionService.increaseVersionAfterCommit(userId);
        // 【批次 6.0.4 · M4】同上：解封的缓存写 0 也放到事务提交后，
        // 保证"库里没解封成功（事务回滚）却把缓存写成正常"这种反向不一致也不会发生。
        TransactionHelper.runAfterCommit(() -> tokenVersionService.cacheUserStatus(userId, USER_NORMAL));
    }

    // ================================================================ 订单

    @Override
    public PageResult<OrderVO> listOrders(AdminOrderQuery query) {
        Page<Order> page = new Page<>(query.current(), query.pageSize());
        IPage<Order> result = orderMapper.selectPage(page, Wrappers.<Order>lambdaQuery()
                .eq(query.getStatus() != null, Order::getStatus, query.getStatus())
                .eq(query.getOrderNo() != null && !query.getOrderNo().isBlank(), Order::getOrderNo, query.getOrderNo())
                .eq(query.getUserId() != null, Order::getUserId, query.getUserId())
                .eq(query.getSellerId() != null, Order::getSellerId, query.getSellerId())
                .orderByDesc(Order::getCreateTime));
        Map<Long, Product> productMap = loadProductsIgnoreLogicDelete(result.getRecords());
        return PageResult.of(result, order -> toOrderVO(order, productMap));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unfreezeOrder(Long id, OrderUnfreezeRequest request) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        if ("CANCEL".equalsIgnoreCase(request.getTarget())) {
            // 5→4：必须同步执行库存回补（【批次 6.0.3 · B1】回补只在这里发生一次，
            // 封禁冻结时不再回补；StockService.restoreOnce 会用 order:restored:{orderId} 兜住重复调用）
            int rows = orderMapper.unfreezeToCancel(id);
            if (rows == 0) {
                throw BusinessException.statusNotAllowed();
            }
            stockService.restoreOnce(id, order.getProductId(), order.getQuantity());
            adminAuditService.record(AuditOperationType.UNFREEZE_ORDER, "ORDER", id, "解冻转已取消并回补库存");
            notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, id,
                    "订单「" + order.getProductTitle() + "」已由管理员解冻并取消");
        } else {
            // 5→3：管理员线下完成
            int rows = orderMapper.unfreezeToComplete(id);
            if (rows == 0) {
                throw BusinessException.statusNotAllowed();
            }
            adminAuditService.record(AuditOperationType.COMPLETE_ORDER, "ORDER", id, "解冻并线下完成");
            notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, id,
                    "订单「" + order.getProductTitle() + "」已由管理员线下处理完成");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceRefund(Long id, String reason) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.noPermission("无权操作该订单");
        }
        // 6/7→4 + 库存回补 + 审计日志
        int rows = orderMapper.forceRefund(id, reason == null ? "管理员强制退款" : reason);
        if (rows == 0) {
            if (order.getStatus() != null && order.getStatus() == OrderStatus.CANCELLED) {
                throw new BusinessException(ErrorCode.SUCCESS, "请勿重复操作");
            }
            throw BusinessException.statusNotAllowed();
        }
        stockService.restoreOnce(id, order.getProductId(), order.getQuantity());
        adminAuditService.record(AuditOperationType.REFUND_ORDER, "ORDER", id, "强制退款原因=" + reason);
        notificationSender.sendAsync(order.getUserId(), TYPE_ORDER, BIZ_TYPE_ORDER, id,
                "订单「" + order.getProductTitle() + "」已由管理员强制退款");
    }

    // ================================================================ 审计日志

    @Override
    public PageResult<AuditLogVO> listAuditLogs(AuditLogQuery query) {
        Page<AuditLog> page = new Page<>(query.current(), query.pageSize());
        IPage<AuditLog> result = auditLogMapper.selectPage(page, Wrappers.<AuditLog>lambdaQuery()
                .eq(query.getOperatorId() != null, AuditLog::getOperatorId, query.getOperatorId())
                .eq(query.getOperationType() != null && !query.getOperationType().isBlank(),
                        AuditLog::getOperationType, query.getOperationType())
                .ge(query.getStartTime() != null, AuditLog::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, AuditLog::getCreateTime, query.getEndTime())
                .orderByDesc(AuditLog::getCreateTime));
        return PageResult.of(result, this::toAuditLogVO);
    }

    // ================================================================ 转换工具

    private Map<Long, String> loadCategoryNames(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = products.stream().map(Product::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
    }

    private Map<Long, Product> loadProductsIgnoreLogicDelete(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyMap();
        }
        Collection<Long> ids = orders.stream().map(Order::getProductId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectByIdsIgnoreLogicDelete(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, User> loadSellers(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = products.stream().map(Product::getUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
    }

    private ProductListVO toProductListVO(Product product, Map<Long, String> categoryNames, Map<Long, User> sellers) {
        ProductListVO vo = new ProductListVO();
        vo.setId(product.getId());
        vo.setTitle(product.getTitle());
        vo.setPrice(product.getPrice());
        vo.setStock(product.getStock());
        vo.setConditionLevel(product.getConditionLevel());
        vo.setTradeType(product.getTradeType());
        vo.setTradeLocation(product.getTradeLocation());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryNames.get(product.getCategoryId()));
        vo.setStatus(product.getStatus());
        vo.setCreateTime(product.getCreateTime());
        vo.setSellerId(product.getUserId());
        if (product.getImageUrls() != null && !product.getImageUrls().isEmpty()) {
            vo.setCoverImage(product.getImageUrls().get(0));
        }
        User seller = sellers.get(product.getUserId());
        if (seller != null) {
            vo.setSellerNickname(seller.getNickname());
            vo.setSellerAvatar(seller.getAvatar());
        }
        return vo;
    }

    private AdminUserVO toAdminUserVO(User user) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    private OrderVO toOrderVO(Order order, Map<Long, Product> productMap) {
        Product product = productMap.get(order.getProductId());
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setSellerId(order.getSellerId());
        vo.setProductId(order.getProductId());
        vo.setProductTitle(order.getProductTitle());
        vo.setProductPrice(order.getProductPrice());
        vo.setAmount(order.getAmount());
        vo.setQuantity(order.getQuantity());
        vo.setStatus(order.getStatus());
        vo.setAddress(order.getAddress());
        vo.setTradeType(order.getTradeType());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setFinishTime(order.getFinishTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setCancelReason(order.getCancelReason());
        vo.setCancelBy(order.getCancelBy());
        vo.setRefundApplyTime(order.getRefundApplyTime());
        vo.setRefundRejectTime(order.getRefundRejectTime());
        vo.setRefundReason(order.getRefundReason());
        vo.setRefundRejectReason(order.getRefundRejectReason());
        vo.setCreateTime(order.getCreateTime());
        if (product == null) {
            vo.setProductDeleted(Boolean.TRUE);
        } else {
            vo.setProductDeleted(Integer.valueOf(1).equals(product.getIsDeleted()));
            if (product.getImageUrls() != null && !product.getImageUrls().isEmpty()) {
                vo.setProductCover(product.getImageUrls().get(0));
            }
        }
        return vo;
    }

    private AuditLogVO toAuditLogVO(AuditLog auditLog) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(auditLog.getId());
        vo.setOperatorId(auditLog.getOperatorId());
        vo.setOperatorName(auditLog.getOperatorName());
        vo.setOperationType(auditLog.getOperationType());
        vo.setTargetType(auditLog.getTargetType());
        vo.setTargetId(auditLog.getTargetId());
        vo.setResult(auditLog.getResult());
        vo.setDetail(auditLog.getDetail());
        vo.setIp(auditLog.getIp());
        vo.setCreateTime(auditLog.getCreateTime());
        return vo;
    }
}
