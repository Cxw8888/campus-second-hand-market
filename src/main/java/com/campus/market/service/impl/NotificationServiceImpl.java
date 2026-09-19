package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.common.query.PageQuery;
import com.campus.market.common.result.PageResult;
import com.campus.market.entity.Notification;
import com.campus.market.mapper.NotificationMapper;
import com.campus.market.security.UserContext;
import com.campus.market.service.NotificationService;
import com.campus.market.service.support.NotificationDispatcher;
import com.campus.market.util.TransactionHelper;
import com.campus.market.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内信服务实现。
 *
 * <p><b>发送侧（6.0.4 · M3 起）</b>：{@code sendAsync} 通过
 * {@link TransactionHelper#runAfterCommit(Runnable)} 把派发推迟到<b>事务提交后</b>，
 * 再交给 {@link NotificationDispatcher} 用专用线程池异步执行
 * （核心 8 / 最大 16 / 队列 200 / CallerRunsPolicy），失败重试 2 次、固定间隔 2 秒，
 * 仍失败记录 error 日志降级。</p>
 *
 * <p><b>发送侧（同步）</b>：{@link #send} 与本事务同库落库，用于测试/降级等需要
 * "通知与业务同生共死"的场景。</p>
 *
 * <p><b>消费侧</b>：list / read / read-all / unread-count。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 未读标记。 */
    private static final int UNREAD = 0;

    /** 已读标记。 */
    private static final int READ = 1;

    private final NotificationMapper notificationMapper;

    /** 异步派发器（独立 Bean：{@code @Async} 必须跨 Bean 调用才走代理）。 */
    private final NotificationDispatcher notificationDispatcher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void send(Long userId, Integer type, Integer bizType, Long bizId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setBizType(bizType);
        notification.setBizId(bizId == null ? 0L : bizId);
        notification.setContent(content);
        notification.setIsRead(UNREAD);
        notificationMapper.insert(notification);
        log.debug("站内信已入库: userId={}, type={}, bizType={}, bizId={}", userId, type, bizType, bizId);
    }

    @Override
    public void sendAsync(Long userId, Integer type, Integer bizType, Long bizId, String content) {
        // 【批次 6.0.4 · M3】必须先过事务提交这道门：
        //   本方法的所有调用点都在 @Transactional 方法体内，事务后段一旦回滚，
        //   修前会立刻把"已支付/已取消/已发货"发出去 —— 用户收到的是假通知。
        //   现在改为：有事务 → 注册 afterCommit 回调；无事务 → 立即派发。
        //   （本方法本身【不再】是 @Async：要在调用线程里注册同步回调，
        //     真正的异步在 NotificationDispatcher.dispatch 上，见其类注释。）
        TransactionHelper.runAfterCommit(() -> notificationDispatcher.dispatch(userId, type, bizType, bizId, content));
    }

    @Override
    public PageResult<NotificationVO> list(Boolean isRead, PageQuery query) {
        Long userId = UserContext.requireUserId();
        long current = query == null ? 1L : query.current();
        long size = query == null ? 10L : query.pageSize();

        Page<Notification> page = new Page<>(current, size);
        IPage<Notification> result = notificationMapper.selectPage(page, Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(isRead != null, Notification::getIsRead, Boolean.TRUE.equals(isRead) ? READ : UNREAD)
                .orderByDesc(Notification::getCreateTime)
                .orderByDesc(Notification::getId));
        return PageResult.of(result, NotificationServiceImpl::toVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long id) {
        Long userId = UserContext.requireUserId();
        Notification notification = notificationMapper.selectOne(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId));
        if (notification == null) {
            // 必须校验归属：通知不存在或不属于当前用户 → 203
            throw BusinessException.noPermission("无权操作该通知");
        }
        if (Integer.valueOf(READ).equals(notification.getIsRead())) {
            // 幂等：已读直接返回成功
            return;
        }
        Notification update = new Notification();
        update.setId(notification.getId());
        update.setIsRead(READ);
        notificationMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        Long userId = UserContext.requireUserId();
        Notification update = new Notification();
        update.setIsRead(READ);
        return notificationMapper.update(update, Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, UNREAD));
    }

    @Override
    public long unreadCount() {
        Long userId = UserContext.requireUserId();
        Long count = notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, UNREAD));
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------------ 内部实现

    private static NotificationVO toVO(Notification notification) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setType(notification.getType());
        vo.setBizType(notification.getBizType());
        vo.setBizId(notification.getBizId());
        vo.setContent(notification.getContent());
        vo.setIsRead(notification.getIsRead());
        vo.setCreateTime(notification.getCreateTime());
        return vo;
    }
}
