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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.5.2 安全加固 · M6-A3 单测：删商品 / 换图时清理磁盘原图。
 *
 * <p>守的是自审报告 M6 ③：修前全仓 {@code grep storageService.delete} <b>零调用方</b>
 * （死代码），删商品或换图之后原图永久留盘 —— 磁盘只增不减。</p>
 *
 * <p>删除的三条护栏都在这里被断言：① 只删"被替换/被删除"的图；② 被其它商品引用的图不删；
 * ③ 删除失败不阻塞业务。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductImageCleanupTest {

    private static final Long PRODUCT_ID = 7101L;
    private static final Long SELLER_ID = 7102L;
    private static final Long CATEGORY_ID = 2L;

    private static final String IMAGE_A = "/static/uploads/product/7102/aaa.jpg";
    private static final String IMAGE_B = "/static/uploads/product/7102/bbb.jpg";
    private static final String IMAGE_C = "/static/uploads/product/7102/ccc.jpg";

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
    }

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productMapper, categoryMapper, orderMapper, userMapper,
                stringRedisTemplate, new ObjectMapper().registerModule(new JavaTimeModule()),
                searchProperties, searchCircuitBreaker, storageService);

        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product(List.of(IMAGE_A, IMAGE_B), ProductStatus.ON_SALE));
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(new Category());
        when(orderMapper.selectCount(any())).thenReturn(0L);
        // 默认：没有其它商品引用这些图
        when(productMapper.countOtherProductsUsingImage(anyLong(), anyString())).thenReturn(0L);

        UserContext.set(new LoginUser(SELLER_ID, 0, 0L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Product product(List<String> imageUrls, int status) {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setUserId(SELLER_ID);
        product.setCategoryId(CATEGORY_ID);
        product.setTitle("高等数学 同济第七版");
        product.setDescription("九成新");
        product.setPrice(new BigDecimal("45.00"));
        product.setStock(1);
        product.setConditionLevel(1);
        product.setTradeType(1);
        product.setTradeLocation("图书馆一楼大厅");
        product.setImageUrls(new ArrayList<>(imageUrls));
        product.setStatus(status);
        return product;
    }

    private ProductSaveRequest requestWithImages(List<String> imageUrls) {
        ProductSaveRequest request = new ProductSaveRequest();
        request.setCategoryId(CATEGORY_ID);
        request.setTitle("高等数学 同济第七版");
        request.setDescription("九成新");
        request.setPrice(new BigDecimal("45.00"));
        request.setStock(1);
        request.setConditionLevel(1);
        request.setTradeType(1);
        request.setTradeLocation("图书馆一楼大厅");
        request.setImageUrls(new ArrayList<>(imageUrls));
        return request;
    }

    @Test
    @DisplayName("① 删除商品 → 图集里的每张图都被清理（StorageService.delete 不再是死代码）")
    void deleteProductShouldRemoveItsImages() {
        productService.delete(PRODUCT_ID);

        verify(storageService).delete(IMAGE_A);
        verify(storageService).delete(IMAGE_B);
    }

    @Test
    @DisplayName("② 换图 → 只删被替换掉的旧图（未变的那张保留）")
    void updateProductShouldRemoveOnlyReplacedImages() {
        // 旧图 [A, B] → 新图 [B, C]：应当只删 A
        productService.update(PRODUCT_ID, requestWithImages(List.of(IMAGE_B, IMAGE_C)));

        verify(storageService).delete(IMAGE_A);
        verify(storageService, never()).delete(IMAGE_B);
        verify(storageService, never()).delete(IMAGE_C);
    }

    @Test
    @DisplayName("③ 图片仍被其它商品引用 → 跳过删除（避免把别人的商品删成无图）")
    void imageReferencedByOthersShouldNotBeDeleted() {
        when(productMapper.countOtherProductsUsingImage(PRODUCT_ID, IMAGE_A)).thenReturn(1L);

        productService.delete(PRODUCT_ID);

        verify(storageService, never()).delete(IMAGE_A);
        verify(storageService).delete(IMAGE_B);
    }

    @Test
    @DisplayName("④ 文件删除失败 → 只告警，不阻塞业务（商品仍被正常删除/更新）")
    void deleteFailureShouldNotBreakBusiness() {
        doThrow(new RuntimeException("磁盘删除失败")).when(storageService).delete(anyString());

        assertThatCode(() -> productService.delete(PRODUCT_ID)).doesNotThrowAnyException();
        // 业务侧的"逻辑删除"确实执行了
        verify(productMapper).deleteById(PRODUCT_ID);
    }

    @Test
    @DisplayName("⑤ 图片未变化 → 一次删除都不触发（不误删正在使用的图）")
    void unchangedImagesShouldNotBeDeleted() {
        productService.update(PRODUCT_ID, requestWithImages(List.of(IMAGE_A, IMAGE_B)));

        verify(storageService, never()).delete(anyString());
        // 没有任何图被替换 → 连"是否被引用"的检查都不需要做
        verify(productMapper, never()).countOtherProductsUsingImage(anyLong(), anyString());
    }
}
