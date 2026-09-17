<script setup>
/**
 * 首页 / 商品列表
 *
 * 数据策略（严格按验收要求实现，三者互斥）：
 *   ① 接口成功且 total > 0  → 渲染真实数据
 *   ② 接口成功但 total === 0 → 显示「暂无商品，快去发布第一件吧」空状态
 *      （注意：这里**不能**用 mock 兜底，否则会把「后端确实没数据」这个事实盖掉）
 *   ③ 接口报错（后端没起 / 网络异常）→ 才用本地 mock 兜底，并 console.warn + 顶部提示条
 *
 * 搜索与分类都写进 URL query 吗？
 *   关键字：写（由 AppHeader 负责），保证可分享、刷新不丢。
 *   分类与排序：只放组件状态。理由：答辩演示时来回切换不需要污染历史记录。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import CategorySidebar from '@/components/CategorySidebar.vue'
import ProductCard from '@/components/ProductCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getProductList } from '@/api/product'
import { mockProductPage } from '@/api/mock'
import { useCategoryStore } from '@/stores/category'
import { DEFAULT_PAGE_SIZE, SORT_OPTIONS, parseSort } from '@/utils/constants'

const route = useRoute()
const router = useRouter()

// 分类数据源与左侧边栏共用（批次 5.4）：hero 上的「商品分类」数量不再写死 6，
// 否则管理员新增分类后会出现「侧边栏 7 个、统计说 6 个」的自相矛盾。
const categoryStore = useCategoryStore()
const categoryCount = computed(() => categoryStore.getList().length)

// ------------------------------------------------------------------ 查询条件
const filters = reactive({
  keyword: '',
  categoryId: null,
  sort: 'create_time-desc',
  minPrice: null,
  maxPrice: null,
  page: 1
})

/** 价格输入框的临时值：只有点了「确定」才真正参与查询，避免每敲一个数字就发一次请求 */
const priceDraft = reactive({ min: null, max: null })

// ------------------------------------------------------------------ 列表状态
const loading = ref(false)
const records = ref([])
const total = ref(0)
/** 是否处于「接口报错 → 本地 mock 兜底」状态 */
const usingMock = ref(false)
/** 兜底时展示的原因，便于演示时解释 */
const fallbackReason = ref('')

const isEmpty = computed(() => !loading.value && records.value.length === 0)

/** 当前是否有筛选条件（用于显示「重置」按钮） */
const hasFilter = computed(
  () =>
    Boolean(filters.keyword) ||
    filters.categoryId != null ||
    filters.minPrice != null ||
    filters.maxPrice != null ||
    filters.sort !== 'create_time-desc'
)

// ------------------------------------------------------------------ 拉取数据
async function fetchList() {
  loading.value = true
  usingMock.value = false
  fallbackReason.value = ''

  const { sortBy, order } = parseSort(filters.sort)

  try {
    // silent: true —— 出错时不弹 ElMessage（我们有自己的兜底提示条，双弹窗很吵）
    const data = await getProductList({
      page: filters.page,
      size: DEFAULT_PAGE_SIZE,
      keyword: filters.keyword || undefined,
      categoryId: filters.categoryId ?? undefined,
      minPrice: filters.minPrice ?? undefined,
      maxPrice: filters.maxPrice ?? undefined,
      sortBy,
      order,
      silent: true
    })

    // ⚠️ total / current 等被后端 Long→String 序列化，必须 Number() 之后再比较
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    // ③ 仅在此分支使用 mock 兜底
    console.warn(
      '[home] 商品列表接口异常，已降级为本地演示数据。请确认后端已启动：http://127.0.0.1:8080 ——',
      error?.message
    )
    const mock = mockProductPage()
    usingMock.value = true
    fallbackReason.value = error?.message || '接口异常'
    total.value = Number(mock.total)
    records.value = mock.records
  } finally {
    loading.value = false
  }
}

/** 条件变化统一走这里：重置回第 1 页再请求 */
function applyFilters() {
  filters.page = 1
  fetchList()
}

function handleSortChange() {
  applyFilters()
}

function handleCategoryChange(categoryId) {
  filters.categoryId = categoryId
  applyFilters()
}

function handlePriceConfirm() {
  // 两个都填且最小 > 最大时直接拦住（后端也会返回 code=100，但没必要多跑一趟）
  if (priceDraft.min != null && priceDraft.max != null && Number(priceDraft.min) > Number(priceDraft.max)) {
    ElMessage.warning('最低价不能大于最高价')
    return
  }
  filters.minPrice = priceDraft.min
  filters.maxPrice = priceDraft.max
  applyFilters()
}

function resetFilters() {
  filters.categoryId = null
  filters.sort = 'create_time-desc'
  filters.minPrice = null
  filters.maxPrice = null
  priceDraft.min = null
  priceDraft.max = null
  filters.page = 1
  if (route.query.keyword) {
    // 清掉 URL 上的关键字（AppHeader 的输入框会跟着 watch 回填）
    router.push({ name: 'home', query: {} })
    return // query 变化会触发 watch → 重新请求
  }
  filters.keyword = ''
  fetchList()
}

function handlePageChange(page) {
  filters.page = page
  fetchList()
  // 翻页后回到列表顶部（商品多时不用手动滚）
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function goPublish() {
  router.push({ name: 'product-publish' })
}

// ------------------------------------------------------------------ 联动
// 关键字来自 AppHeader 写进 URL 的 query
watch(
  () => route.query.keyword,
  (value) => {
    filters.keyword = String(value || '')
    applyFilters()
  }
)

onMounted(() => {
  filters.keyword = String(route.query.keyword || '')
  fetchList()
  // 分类首屏不阻塞：不 await，请求在后台跑（与 CategorySidebar 的调用靠 store 去重，只会发一次）
  categoryStore.ensureLoaded()
})
</script>

<template>
  <main class="home">
    <!-- 顶部轻量 hero：只占一小条，避免把商品列表挤到首屏之外 -->
    <section class="home__hero">
      <div class="cm-container home__hero-inner">
        <div>
          <h1 class="home__hero-title">
            让闲置<span class="home__hero-accent">流动</span>起来
          </h1>
          <p class="home__hero-sub">校内同学之间的二手交易 —— 便宜、放心、就在身边</p>
        </div>
        <div class="home__hero-stats">
          <div class="home__stat">
            <span class="home__stat-num cm-num">{{ usingMock ? '—' : total }}</span>
            <span class="home__stat-label">在售商品</span>
          </div>
          <div class="home__stat">
            <span class="home__stat-num cm-num">{{ categoryCount }}</span>
            <span class="home__stat-label">商品分类</span>
          </div>
        </div>
      </div>
    </section>

    <div class="cm-container home__body">
      <!-- 左：分类 -->
      <CategorySidebar :model-value="filters.categoryId" @update:model-value="handleCategoryChange" />

      <!-- 右：筛选 + 列表 -->
      <section class="home__main">
        <!-- 兜底提示条：只在接口报错时出现 -->
        <el-alert
          v-if="usingMock"
          class="home__fallback"
          type="warning"
          show-icon
          :closable="false"
          title="未能连接后端，当前展示的是本地演示数据"
        >
          <template #default>
            <span class="home__fallback-text">
              原因：{{ fallbackReason }}。请确认后端已启动（http://127.0.0.1:8080），然后点右侧重试。
            </span>
            <el-button size="small" :icon="Refresh" class="home__fallback-btn" @click="fetchList">
              重试
            </el-button>
          </template>
        </el-alert>

        <!-- 筛选区 -->
        <div class="home__filter">
          <div class="home__filter-left">
            <el-radio-group v-model="filters.sort" size="default" @change="handleSortChange">
              <el-radio-button v-for="opt in SORT_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </el-radio-button>
            </el-radio-group>

            <div class="home__price">
              <span class="home__price-label">价格区间</span>
              <el-input-number
                v-model="priceDraft.min"
                :min="0"
                :controls="false"
                placeholder="最低"
                class="home__price-input"
              />
              <span class="home__price-sep">—</span>
              <el-input-number
                v-model="priceDraft.max"
                :min="0"
                :controls="false"
                placeholder="最高"
                class="home__price-input"
              />
              <el-button @click="handlePriceConfirm">确定</el-button>
            </div>
          </div>

          <div class="home__filter-right">
            <span v-if="!usingMock" class="home__count">
              共 <b class="cm-num">{{ total }}</b> 件商品
            </span>
            <el-button v-if="hasFilter" link type="primary" :icon="Refresh" @click="resetFilters">
              重置筛选
            </el-button>
          </div>
        </div>

        <!-- 当前关键字回显 -->
        <div v-if="filters.keyword" class="home__keyword">
          <el-icon :size="13"><Search /></el-icon>
          搜索「<b>{{ filters.keyword }}</b>」的结果
        </div>

        <!-- 列表：加载态由下面的骨架屏承担，所以这里不用 v-loading 指令 -->
        <div class="home__list-wrap">
          <div v-if="records.length" class="home__grid">
            <ProductCard v-for="item in records" :key="item.id" :product="item" />
          </div>

          <!-- ② 接口成功但零条：空状态（不使用 mock） -->
          <EmptyState
            v-else-if="isEmpty && !usingMock"
            title="暂无商品，快去发布第一件吧"
            description="这里还没有在售的闲置物品。点下面的按钮发布你的第一件商品，或者换个分类、清空筛选条件再看看。"
            action-text="发布商品"
            @action="goPublish"
          />

          <!-- 兜底数据理论上不会是空的，这里只做保险 -->
          <EmptyState
            v-else-if="isEmpty && usingMock"
            title="演示数据也空了"
            description="这不该发生，请检查 src/api/mock.js。"
          />

          <div v-else class="home__grid-skeleton">
            <!-- 加载中的骨架：保持 3 列网格，避免布局跳动 -->
            <div v-for="n in 6" :key="n" class="home__skeleton-card">
              <el-skeleton animated>
                <template #template>
                  <el-skeleton-item variant="image" style="width: 100%; height: 150px" />
                  <div style="padding: 12px 14px">
                    <el-skeleton-item variant="text" style="width: 80%" />
                    <el-skeleton-item variant="text" style="width: 45%; margin-top: 10px" />
                  </div>
                </template>
              </el-skeleton>
            </div>
          </div>
        </div>

        <!-- 分页：兜底数据不分页 -->
        <div v-if="!usingMock && total > DEFAULT_PAGE_SIZE" class="home__pager">
          <el-pagination
            background
            layout="prev, pager, next, jumper, total"
            :total="total"
            :page-size="DEFAULT_PAGE_SIZE"
            :current-page="filters.page"
            @current-change="handlePageChange"
          />
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped lang="scss">
.home {
  flex: 1;
  padding-bottom: 56px;

  // ---------------- hero ----------------
  &__hero {
    background:
      radial-gradient(720px 220px at 12% 0%, rgba(16, 185, 129, 0.14), transparent 70%),
      linear-gradient(180deg, $cm-primary-50 0%, rgba(249, 250, 251, 0) 100%);
    padding: 28px 0 22px;
    border-bottom: 1px solid rgba(16, 185, 129, 0.1);
  }

  &__hero-inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
  }

  &__hero-title {
    font-size: 26px;
    font-weight: 800;
    letter-spacing: 1px;
    color: $cm-text;
    margin-bottom: 6px;
  }

  &__hero-accent {
    color: $cm-primary;
  }

  &__hero-sub {
    font-size: 13px;
    color: $cm-text-secondary;
  }

  &__hero-stats {
    display: flex;
    gap: 30px;
    flex: none;
  }

  &__stat {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  &__stat-num {
    font-size: 22px;
    font-weight: 800;
    color: $cm-primary;
    line-height: 1.1;
  }

  &__stat-label {
    font-size: 12px;
    color: $cm-text-secondary;
  }

  // ---------------- 主体两栏 ----------------
  &__body {
    display: flex;
    gap: 20px;
    align-items: flex-start;
    padding-top: 20px;
  }

  &__main {
    flex: 1;
    min-width: 0;
  }

  // ---------------- 兜底提示 ----------------
  &__fallback {
    margin-bottom: 16px;
    border-radius: $cm-radius;

    :deep(.el-alert__content) {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      width: 100%;
    }
  }

  &__fallback-text {
    font-size: 12px;
    line-height: 1.6;
  }

  &__fallback-btn {
    flex: none;
  }

  // ---------------- 筛选区 ----------------
  &__filter {
    @include cm-card;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
    padding: 14px 16px;
    margin-bottom: 16px;
  }

  &__filter-left {
    display: flex;
    align-items: center;
    gap: 18px;
    flex-wrap: wrap;
  }

  &__filter-right {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-left: auto;
  }

  &__price {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__price-label {
    font-size: 13px;
    color: $cm-text-secondary;
    white-space: nowrap;
  }

  &__price-input {
    width: 88px;

    :deep(.el-input__wrapper) {
      border-radius: $cm-radius;
    }
  }

  &__price-sep {
    color: $cm-text-placeholder;
  }

  &__count {
    font-size: 13px;
    color: $cm-text-secondary;

    b {
      color: $cm-primary;
      font-size: 15px;
    }
  }

  &__keyword {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 14px;
    padding: 5px 12px;
    border-radius: $cm-radius-pill;
    font-size: 12px;
    color: $cm-primary-700;
    background: $cm-primary-50;

    b {
      font-weight: 600;
    }
  }

  // ---------------- 列表 ----------------
  &__list-wrap {
    min-height: 280px;
  }

  &__grid,
  &__grid-skeleton {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 18px;
  }

  &__skeleton-card {
    @include cm-card;
    overflow: hidden;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 28px;
  }

  // ---------------- 响应式 ----------------
  @include cm-max($cm-bp-lg) {
    &__grid,
    &__grid-skeleton {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @include cm-max($cm-bp-md) {
    &__body {
      flex-direction: column;
    }

    &__hero-inner {
      flex-direction: column;
      align-items: flex-start;
    }

    &__hero-stats {
      gap: 22px;
    }
  }

  @include cm-max($cm-bp-sm) {
    &__grid,
    &__grid-skeleton {
      grid-template-columns: minmax(0, 1fr);
    }
  }
}
</style>
