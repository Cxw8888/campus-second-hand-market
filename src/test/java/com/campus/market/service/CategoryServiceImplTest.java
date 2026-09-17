package com.campus.market.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.market.common.constant.RedisKeys;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.product.CategoryMigrateRequest;
import com.campus.market.dto.product.CategorySaveRequest;
import com.campus.market.entity.Category;
import com.campus.market.entity.Product;
import com.campus.market.mapper.CategoryMapper;
import com.campus.market.mapper.ProductMapper;
import com.campus.market.service.impl.CategoryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类服务单测（批次 5.4.2 缺陷修复）。
 *
 * <h3>本批修的缺陷</h3>
 * {@code uk_category_name(name)} 是单列唯一索引、不含 {@code is_deleted}，而重名预检
 * {@code existsByName} 走 MyBatis-Plus 会自动追加 {@code is_deleted = 0} ——
 * 两边口径不一致，导致"已删除的分类仍占着名字，新建同名分类必抛 DuplicateKeyException → 500"。
 * 修复手段是：逻辑删除时同步把 name 改成 {@code 原名#deleted{id}}，把名字还给后来的分类。
 *
 * <h3>为什么用纯 Mockito 而不是 @SpringBootTest</h3>
 * 这里要钉死的是"delete() 到底发出了哪条 UPDATE"这种**行为契约**，用假 mapper 断言得最准，
 * 而且不依赖 MySQL / Redis（项目里已有依赖真实环境的用例，没必要让这条也变重）。
 * "删除后同名真的能再建出来"属于数据库层事实，由接口实测覆盖（见批次报告 H-a）。
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    private static final Long CATEGORY_ID = 11L;
    private static final Long TARGET_ID = 2L;
    private static final String CATEGORY_NAME = "乐器";

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    /**
     * 纯单测里没有 MyBatis-Plus 运行时，需要手工初始化实体元信息。
     *
     * <p>原因：{@code migrate()} 里用了 {@code lambdaQuery().select(Product::getId)}，
     * 而 {@code select(...)} 会**立即**把方法引用翻译成列名，翻译依赖 TableInfo 缓存；
     * 缓存缺失时报 {@code can not find lambda cache for this entity}。
     * （{@code eq(...)} 是懒翻译，所以 {@code delete()} 的用例不需要这一步。）
     * 这里把 {@link Product} 的元信息灌进去，等价于 MP 启动时做的事。</p>
     */
    @BeforeAll
    static void initMybatisPlusEntityMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
    }

    // ------------------------------------------------------------ 任务 D-1/D-2：删除让位改名

    @Test
    @DisplayName("D-2 删除分类 → 先把 name 改成『原名#deleted{id}』让位，再逻辑删除，并失效列表缓存")
    void deleteShouldRenameBeforeLogicalDelete() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category(CATEGORY_ID, CATEGORY_NAME, 0));
        when(productMapper.selectCount(any())).thenReturn(0L);

        categoryService.delete(CATEGORY_ID);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).updateById(captor.capture());

        Category renamed = captor.getValue();
        assertThat(renamed.getId()).as("改名必须定向到被删的那一行").isEqualTo(CATEGORY_ID);
        assertThat(renamed.getName())
                .as("name 必须带 #deleted{id} 后缀，才能腾出 uk_category_name 的唯一索引占用")
                .isEqualTo("乐器#deleted11");
        assertThat(renamed.getSort())
                .as("只改 name：sort 不参与赋值，MyBatis-Plus 的非空字段策略下不会被覆盖成 null")
                .isNull();

        verify(categoryMapper).deleteById(CATEGORY_ID);
        verify(stringRedisTemplate).delete(RedisKeys.PRODUCT_CATEGORY_LIST);
    }

    @Test
    @DisplayName("D-2 让位后的名字可还原原名（去掉后缀即可），审计与排查不丢信息")
    void renamedNameShouldKeepOriginalRecoverable() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category(CATEGORY_ID, CATEGORY_NAME, 0));
        when(productMapper.selectCount(any())).thenReturn(0L);

        categoryService.delete(CATEGORY_ID);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).updateById(captor.capture());
        String renamed = captor.getValue().getName();

        assertThat(renamed).startsWith(CATEGORY_NAME);
        assertThat(renamed.replace("#deleted" + CATEGORY_ID, "")).isEqualTo(CATEGORY_NAME);
    }

    @Test
    @DisplayName("D-2 同名分类被不同 id 反复删除 → 后缀互不相同，不会互相撞唯一索引")
    void suffixShouldContainIdSoRepeatedDeletesDoNotCollide() {
        when(categoryMapper.selectById(11L)).thenReturn(category(11L, CATEGORY_NAME, 0));
        when(categoryMapper.selectById(12L)).thenReturn(category(12L, CATEGORY_NAME, 0));
        when(productMapper.selectCount(any())).thenReturn(0L);

        categoryService.delete(11L);
        categoryService.delete(12L);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper, times(2)).updateById(captor.capture());
        List<String> names = captor.getAllValues().stream().map(Category::getName).toList();
        assertThat(names).containsExactly("乐器#deleted11", "乐器#deleted12");
    }

    @Test
    @DisplayName("D-2 名字里已有 #deleted 时不重复追加（幂等，与清理脚本判据一致）")
    void deleteShouldNotAppendSuffixTwice() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category(CATEGORY_ID, "乐器#deleted11", 99));
        when(productMapper.selectCount(any())).thenReturn(0L);

        categoryService.delete(CATEGORY_ID);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).updateById(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("乐器#deleted11");
    }

    @Test
    @DisplayName("D-2 原名已达 50 字上限 → 按『后缀优先』截断，改名本身不能撞 name VARCHAR(50)")
    void deleteShouldTruncateSoRenamedValueFitsColumn() {
        String longName = "分类".repeat(25); // 50 字，正好是列上限
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category(CATEGORY_ID, longName, 0));
        when(productMapper.selectCount(any())).thenReturn(0L);

        categoryService.delete(CATEGORY_ID);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).updateById(captor.capture());
        String renamed = captor.getValue().getName();

        assertThat(renamed.length()).as("总长不得超过 50").isLessThanOrEqualTo(50);
        assertThat(renamed).endsWith("#deleted11");
        assertThat(longName).startsWith(renamed.substring(0, renamed.indexOf("#deleted")));
    }

    // ------------------------------------------------------------ 任务 D-5：208 回归

    @Test
    @DisplayName("D-5 回归：分类下仍有商品 → 仍然抛 208，且不发生任何写操作（改名也不能发生）")
    void deleteShouldStillRejectWhenCategoryHasProducts() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category(CATEGORY_ID, CATEGORY_NAME, 0));
        when(productMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> categoryService.delete(CATEGORY_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CATEGORY_HAS_PRODUCT.getCode());
                    assertThat(ex.getMessage()).isEqualTo(ErrorCode.CATEGORY_HAS_PRODUCT.getMsg());
                });

        verify(categoryMapper, never()).updateById(any());
        verify(categoryMapper, never()).deleteById(any());
        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    @Test
    @DisplayName("删除不存在的分类 → 100（不是 500），同样不产生写操作")
    void deleteShouldRejectMissingCategory() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(null);

        assertThatThrownBy(() -> categoryService.delete(CATEGORY_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
                    assertThat(ex.getMessage()).isEqualTo("分类不存在");
                });

        verify(categoryMapper, never()).updateById(any());
        verify(categoryMapper, never()).deleteById(any());
    }

    // ------------------------------------------------------------ 任务 D-3：重名 → 100

    @Test
    @DisplayName("D-3 重名预检命中（未删除的同名分类）→ 抛 100『分类名称已存在』，不执行 INSERT")
    void createShouldRejectDuplicateNameWithCode100() {
        CategorySaveRequest request = new CategorySaveRequest();
        request.setName(CATEGORY_NAME);
        request.setSort(0);
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> categoryService.create(request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode())
                            .as("重名必须走 100（参数校验），绝不能落到 500")
                            .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
                    assertThat(ex.getMessage()).isEqualTo("分类名称已存在");
                });

        verify(categoryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("新建分类成功 → 写入 name/sort（不显式设置 is_deleted，交给列默认值 0）并失效缓存")
    void createShouldInsertAndEvictCache() {
        CategorySaveRequest request = new CategorySaveRequest();
        request.setName("  乐器  ");
        request.setSort(null); // 后端约定：null 兜 0
        when(categoryMapper.selectCount(any())).thenReturn(0L);

        categoryService.create(request);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).as("入库前必须 trim").isEqualTo("乐器");
        assertThat(captor.getValue().getSort()).isEqualTo(0);
        verify(stringRedisTemplate).delete(RedisKeys.PRODUCT_CATEGORY_LIST);
    }

    // ------------------------------------------------------------ 任务 D-4：migrate 空分类也要失效缓存

    @Test
    @DisplayName("D-4 源分类下没有商品（提前 return）→ 仍必须失效列表缓存")
    void migrateShouldEvictCacheEvenWhenNothingToMove() {
        CategoryMigrateRequest request = migrateRequest();
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category(CATEGORY_ID, CATEGORY_NAME, 0));
        when(categoryMapper.selectById(TARGET_ID)).thenReturn(category(TARGET_ID, "数码电子", 20));
        when(productMapper.selectList(any())).thenReturn(List.of());

        int moved = categoryService.migrate(request);

        assertThat(moved).isZero();
        verify(productMapper, never()).update(any(), any());
        verify(stringRedisTemplate)
                .delete(RedisKeys.PRODUCT_CATEGORY_LIST);
    }

    @Test
    @DisplayName("D-4 源分类与目标分类相同 → 100，且不做任何写与缓存操作")
    void migrateShouldRejectSameSourceAndTarget() {
        CategoryMigrateRequest request = new CategoryMigrateRequest();
        request.setFromCategoryId(CATEGORY_ID);
        request.setToCategoryId(CATEGORY_ID);

        assertThatThrownBy(() -> categoryService.migrate(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));

        verify(productMapper, never()).update(any(), any());
        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    // ------------------------------------------------------------ 工具

    private static Category category(Long id, String name, Integer sort) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setSort(sort);
        return category;
    }

    private static CategoryMigrateRequest migrateRequest() {
        CategoryMigrateRequest request = new CategoryMigrateRequest();
        request.setFromCategoryId(CATEGORY_ID);
        request.setToCategoryId(TARGET_ID);
        return request;
    }
}
