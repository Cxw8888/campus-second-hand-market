package com.campus.market.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.SearchProperties;
import com.campus.market.config.properties.StorageProperties;
import com.campus.market.dto.product.ProductSaveRequest;
import com.campus.market.entity.Category;
import com.campus.market.entity.Order;
import com.campus.market.entity.Product;
import com.campus.market.entity.enums.OrderStatus;
import com.campus.market.mapper.CategoryMapper;
import com.campus.market.mapper.OrderMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.mapper.UserMapper;
import com.campus.market.security.LoginUser;
import com.campus.market.security.UserContext;
import com.campus.market.service.impl.ProductServiceImpl;
import com.campus.market.service.support.SearchCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.6 · 自审 Minor 1 + Minor 8 单测（都落在 {@code ProductServiceImpl}）。
 *
 * <ul>
 *   <li><b>Minor 1</b>：未完成订单集合补 {@link OrderStatus#FROZEN}（5-已冻结）。
 *       修前冻结订单不在集合里 → 卖家能删掉"被封禁冻结"订单关联的商品 →
 *       管理员解冻选 CANCEL 时回补 SQL 因 {@code is_deleted = 0} 命中 0 行，<b>库存静默丢失</b>。</li>
 *   <li><b>Minor 8</b>：图片地址协议 / 前缀白名单。修前只校验"非空 + ≤9 张"，
 *       任意字符串都能进库并原样渲染到 {@code <img src>}（外链追踪像素 / 以图引流；
 *       {@code javascript:} 在 img 上不执行，不构成 XSS，但同样不该入库）。</li>
 * </ul>
 *
 * <p>Minor 1 的断言方式：捕获 {@code orderMapper.selectCount(...)} 的 {@link Wrapper}，
 * 读它收集到的参数值 —— 直接证明"冻结状态真的进了 SQL 的 IN 列表"，
 * 而不是只证明"某个 guard 抛了 207"（后者在 mock 下恒成立，证明不了集合内容）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductLifecycleMinorTest {

    private static final Long PRODUCT_ID = 6601L;
    private static final Long SELLER_ID = 6602L;
    private static final Long CATEGORY_ID = 2L;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SearchProperties searchProperties;

    @Mock
    private SearchCircuitBreaker searchCircuitBreaker;

    @Mock
    private StorageService storageService;

    private ProductServiceImpl productService;

    @BeforeAll
    static void initMybatisPlusEntityMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productMapper, categoryMapper, orderMapper, userMapper,
                stringRedisTemplate, new ObjectMapper().registerModule(new JavaTimeModule()),
                searchProperties, searchCircuitBreaker, storageService, new StorageProperties());

        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setUserId(SELLER_ID);
        product.setCategoryId(CATEGORY_ID);
        product.setTitle("考研英语真题 九成新");
        product.setDescription("只做过两套");
        product.setPrice(new BigDecimal("35.00"));
        product.setStock(1);
        product.setConditionLevel(2);
        product.setTradeType(1);
        product.setTradeLocation("三食堂门口");
        product.setImageUrls(new ArrayList<>(List.of("/static/uploads/demo1.jpg")));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(new Category());

        UserContext.set(new LoginUser(SELLER_ID, 0, 0L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ProductSaveRequest baseRequest(String firstImageUrl) {
        ProductSaveRequest request = new ProductSaveRequest();
        request.setCategoryId(CATEGORY_ID);
        request.setTitle("考研英语真题 九成新");
        request.setDescription("只做过两套");
        request.setPrice(new BigDecimal("35.00"));
        request.setStock(1);
        request.setConditionLevel(2);
        request.setTradeType(1);
        request.setTradeLocation("三食堂门口");
        request.setImageUrls(new ArrayList<>(List.of(firstImageUrl)));
        return request;
    }

    // ================================================================ Minor 1

    @Test
    @DisplayName("Minor 1 ① 未完成订单查询条件必须包含 5-已冻结（否则冻结订单的商品可被删除）")
    void unfinishedOrderStatusMustContainFrozen() {
        when(orderMapper.selectCount(any())).thenReturn(0L);

        productService.delete(PRODUCT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).selectCount(captor.capture());
        Wrapper<Order> wrapper = captor.getValue();
        assertThat(wrapper).isInstanceOf(AbstractWrapper.class);

        // ⚠️ MyBatis-Plus 是"懒格式化"：参数值只有在 getSqlSegment() 被调用时才灌进
        // paramNameValuePairs（实测：调用前是空 Map）。所以必须先取一次 SQL 片段。
        String sqlSegment = wrapper.getSqlSegment();
        assertThat(sqlSegment).as("where 片段：%s", sqlSegment).contains("status IN (");

        // `.in(column, Collection)` 会把每个元素摊成一个独立占位符（MPGENVALx），
        // 这里再兼容一下"整个集合作为一个值"的形态，避免 MP 版本差异让用例偶发失效。
        List<Object> params = new ArrayList<>();
        for (Object value : ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values()) {
            if (value instanceof Collection<?> collection) {
                params.addAll(collection);
            } else {
                params.add(value);
            }
        }

        assertThat(params)
                .as("IN 列表里的状态值应包含 0/1/2/6/7/5，实际 %s", params)
                .contains(OrderStatus.PENDING_PAY, OrderStatus.PAID, OrderStatus.SHIPPED,
                        OrderStatus.REFUND_APPLYING, OrderStatus.REFUND_REJECTED, OrderStatus.FROZEN);
    }

    @Test
    @DisplayName("Minor 1 ② 存在冻结订单（status=5）→ 删除被拒 code=207，且不落删除")
    void deleteIsRejectedWhenFrozenOrderExists() {
        when(orderMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> productService.delete(PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.PRODUCT_HAS_ORDER.getCode()));

        verify(productMapper, never()).deleteById(any(Long.class));
    }

    // ================================================================ Minor 8

    @Test
    @DisplayName("Minor 8 ① 非白名单协议（javascript: / data: / file: / 协议相对）→ code=100")
    void imageUrlWithUnexpectedSchemeIsRejected() {
        List<String> rejected = List.of(
                "javascript:alert(1)",
                "data:image/png;base64,iVBORw0KGgo=",
                "file:///C:/Windows/win.ini",
                "//evil.example.com/track.png",
                "httpx://evil.example.com/track.png",
                "/other/uploads/x.jpg");

        for (String url : rejected) {
            assertThatThrownBy(() -> productService.create(baseRequest(url)))
                    .as("应拒绝的图片地址：%s", url)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
        }
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    @DisplayName("Minor 8 ② 站内上传地址（/static/uploads/...）与 http(s) 绝对地址 → 放行")
    void allowedImageUrlsPassValidation() {
        List<String> allowed = List.of(
                "/static/uploads/product/1/abc.jpg",
                "http://cdn.example.com/a.jpg",
                "HTTPS://cdn.example.com/a.jpg");

        for (String url : allowed) {
            assertThatCode(() -> productService.create(baseRequest(url)))
                    .as("应放行的图片地址：%s", url)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Minor 8 ③ 白名单前缀取自配置（app.storage.local.url-prefix），换前缀时校验自动跟随")
    void whitelistPrefixFollowsConfiguration() {
        StorageProperties custom = new StorageProperties();
        custom.getLocal().setUrlPrefix("/media/uploads/");
        ProductServiceImpl customized = new ProductServiceImpl(productMapper, categoryMapper, orderMapper,
                userMapper, stringRedisTemplate, new ObjectMapper().registerModule(new JavaTimeModule()),
                searchProperties, searchCircuitBreaker, storageService, custom);

        // 新前缀放行（注意配置里结尾带 '/' 也要能拼对）
        assertThatCode(() -> customized.create(baseRequest("/media/uploads/product/1/a.jpg")))
                .doesNotThrowAnyException();
        // 老前缀此时不在白名单里
        assertThatThrownBy(() -> customized.create(baseRequest("/static/uploads/product/1/a.jpg")))
                .isInstanceOf(BusinessException.class);
    }
}
