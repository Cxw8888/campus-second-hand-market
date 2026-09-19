package com.campus.market.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.market.config.properties.SearchProperties;
import com.campus.market.dto.product.ProductSaveRequest;
import com.campus.market.entity.Category;
import com.campus.market.entity.Product;
import com.campus.market.entity.enums.ProductStatus;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.5.1 安全加固 · M5 单测：售罄商品编辑关键字段必须走重新审核。
 *
 * <p>守的是自审报告 M5：{@code resolveStatus} 的 SOLD_OUT 分支修前只看新库存、
 * <b>完全不看 criticalChanged</b> —— "售罄 → 改价/改标题/换图 + 补库存 → 直接 status=1 上架"，
 * 整段跳过审核。而售罄商品往往已经过一轮审核，等于拿"已审核"的壳子换成任意内容后立刻重新在售。</p>
 *
 * <p>这里走<b>真实的 {@code ProductServiceImpl.update()}</b>（而不是反射调私有方法），
 * 这样 {@code isCriticalChanged} 与 {@code resolveStatus} 的配合也被一并覆盖；
 * 断言方式是捕获写库实体（{@code updateById} 的参数）里的 status 字段。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductSoldOutEditReviewTest {

    private static final Long PRODUCT_ID = 6201L;
    private static final Long SELLER_ID = 6202L;
    private static final Long CATEGORY_ID = 2L;

    private static final String OLD_TITLE = "高等数学 同济第七版 九成新";
    private static final String OLD_DESCRIPTION = "只翻过前两章，无笔记";
    private static final String OLD_LOCATION = "图书馆一楼大厅";
    private static final List<String> OLD_IMAGES = new ArrayList<>(List.of("/static/uploads/demo1.jpg"));

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

    private ProductServiceImpl productService;

    /** 补齐 TableInfo：{@code update()} 里的 {@code lambdaUpdate().set(...)} 会立即翻译列名。 */
    @BeforeAll
    static void initMybatisPlusEntityMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
    }

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productMapper, categoryMapper, orderMapper, userMapper,
                stringRedisTemplate, new ObjectMapper().registerModule(new JavaTimeModule()),
                searchProperties, searchCircuitBreaker);

        Product soldOut = new Product();
        soldOut.setId(PRODUCT_ID);
        soldOut.setUserId(SELLER_ID);
        soldOut.setCategoryId(CATEGORY_ID);
        soldOut.setTitle(OLD_TITLE);
        soldOut.setDescription(OLD_DESCRIPTION);
        soldOut.setPrice(new BigDecimal("45.00"));
        soldOut.setStock(0);
        soldOut.setConditionLevel(1);
        soldOut.setTradeType(1);
        soldOut.setTradeLocation(OLD_LOCATION);
        soldOut.setImageUrls(new ArrayList<>(OLD_IMAGES));
        soldOut.setStatus(ProductStatus.SOLD_OUT);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(soldOut);
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(new Category());

        UserContext.set(new LoginUser(SELLER_ID, 0, 0L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 以"售罄商品原始状态"为基线构造 PUT 全量更新请求，再用 mutator 改需要变的字段。 */
    private ProductSaveRequest baseRequest() {
        ProductSaveRequest request = new ProductSaveRequest();
        request.setCategoryId(CATEGORY_ID);
        request.setTitle(OLD_TITLE);
        request.setDescription(OLD_DESCRIPTION);
        request.setPrice(new BigDecimal("45.00"));
        request.setStock(0);
        request.setConditionLevel(1);
        request.setTradeType(1);
        request.setTradeLocation(OLD_LOCATION);
        request.setImageUrls(new ArrayList<>(OLD_IMAGES));
        return request;
    }

    /**
     * 执行一次编辑，返回实际写库的状态（updateById 的实体参数）。
     *
     * <p>用 {@code atLeastOnce() + 取最后一次} 而不是 {@code verify(...)} 单次断言：
     * 有两条用例会在同一个方法里连着编辑两次（覆盖不同字段），
     * 若固定断言"只调用一次"，第二次调用会把用例打挂 —— 那是用例写法问题，不是产品缺陷。</p>
     */
    private int updateAndCaptureStatus(ProductSaveRequest request) {
        productService.update(PRODUCT_ID, request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper, atLeastOnce()).updateById(captor.capture());
        List<Product> written = captor.getAllValues();
        return written.get(written.size() - 1).getStatus();
    }

    @Test
    @DisplayName("① 售罄商品改标题 + 补库存 → 3-待审核（修复前会直接回到 1-上架中）")
    void changingTitleShouldRequireReAudit() {
        ProductSaveRequest request = baseRequest();
        request.setTitle("换了标题的教材（含笔记）");
        request.setStock(1);

        assertThat(updateAndCaptureStatus(request)).isEqualTo(ProductStatus.PENDING_AUDIT);
    }

    @Test
    @DisplayName("② 售罄商品改价格 + 补库存 → 3-待审核")
    void changingPriceShouldRequireReAudit() {
        ProductSaveRequest request = baseRequest();
        request.setPrice(new BigDecimal("99.00"));
        request.setStock(1);

        assertThat(updateAndCaptureStatus(request)).isEqualTo(ProductStatus.PENDING_AUDIT);
    }

    @Test
    @DisplayName("③ 售罄商品改描述 / 换图 / 改成色 + 补库存 → 3-待审核（其余关键字段一并覆盖）")
    void otherCriticalFieldsShouldAlsoRequireReAudit() {
        ProductSaveRequest descriptionChanged = baseRequest();
        descriptionChanged.setDescription("全新描述：已做满笔记");
        descriptionChanged.setStock(1);
        assertThat(updateAndCaptureStatus(descriptionChanged)).isEqualTo(ProductStatus.PENDING_AUDIT);

        ProductSaveRequest imagesChanged = baseRequest();
        imagesChanged.setImageUrls(new ArrayList<>(List.of("/static/uploads/demo2.jpg")));
        imagesChanged.setStock(1);
        assertThat(updateAndCaptureStatus(imagesChanged)).isEqualTo(ProductStatus.PENDING_AUDIT);

        ProductSaveRequest conditionChanged = baseRequest();
        conditionChanged.setConditionLevel(4);
        conditionChanged.setStock(1);
        assertThat(updateAndCaptureStatus(conditionChanged)).isEqualTo(ProductStatus.PENDING_AUDIT);
    }

    @Test
    @DisplayName("④ 售罄商品只改面交地点（非关键）+ 补库存 → 1-上架中（直接生效，无需重审）")
    void nonCriticalChangeShouldTakeEffectDirectly() {
        ProductSaveRequest request = baseRequest();
        request.setTradeLocation("三食堂门口");
        request.setStock(1);

        assertThat(updateAndCaptureStatus(request)).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("⑤ 售罄商品什么都不改 + 补库存 → 1-上架中（原有正常经营动作保持不变）")
    void restockWithoutEditShouldRelist() {
        ProductSaveRequest request = baseRequest();
        request.setStock(1);

        assertThat(updateAndCaptureStatus(request)).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("⑥ 回归：售罄商品只改非关键字段但【不】补库存 → 仍是 2-售罄（不能凭空上架）")
    void nonCriticalChangeWithoutRestockShouldStaySoldOut() {
        ProductSaveRequest request = baseRequest();
        request.setTradeLocation("南门车棚");
        request.setStock(0);

        assertThat(updateAndCaptureStatus(request)).isEqualTo(ProductStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("⑦ 回归：在售商品的关键字段变更同样重审（本批未改这条既有规则）")
    void onSaleCriticalChangeStillRequiresReAudit() {
        Product onSale = new Product();
        onSale.setId(PRODUCT_ID);
        onSale.setUserId(SELLER_ID);
        onSale.setCategoryId(CATEGORY_ID);
        onSale.setTitle(OLD_TITLE);
        onSale.setDescription(OLD_DESCRIPTION);
        onSale.setPrice(new BigDecimal("45.00"));
        onSale.setStock(3);
        onSale.setConditionLevel(1);
        onSale.setTradeType(1);
        onSale.setTradeLocation(OLD_LOCATION);
        onSale.setImageUrls(new ArrayList<>(OLD_IMAGES));
        onSale.setStatus(ProductStatus.ON_SALE);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(onSale);

        ProductSaveRequest critical = baseRequest();
        critical.setPrice(new BigDecimal("50.00"));
        critical.setStock(3);
        assertThat(updateAndCaptureStatus(critical)).isEqualTo(ProductStatus.PENDING_AUDIT);

        ProductSaveRequest nonCritical = baseRequest();
        nonCritical.setTradeLocation("东门快递柜旁");
        nonCritical.setStock(3);
        assertThat(updateAndCaptureStatus(nonCritical)).isEqualTo(ProductStatus.ON_SALE);
    }
}
