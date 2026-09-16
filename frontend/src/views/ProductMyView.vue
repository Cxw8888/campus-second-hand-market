<script setup>
/**
 * 我的商品 /product/my?tab=all|on|pending|off|soldout
 *
 * 数据源：GET /api/v1/product/my（卖家视角，含待审核），按 status 筛 Tab。
 *
 * Tab 角标数量的取法：对 5 个状态各发一次 `size=1` 的轻量请求，只取 total，
 * 比「拉全量再前端统计」更准确（后端 size 上限 100，商品多了前端统计会失真）。
 *
 * 防重复提交：所有操作按钮共用一把「同步锁」busyId。
 * 注意锁必须在**弹确认框之前**就置位 —— 否则连点照样会堆叠出多个确认框
 * （下单页当初就是栽在这里，详见 OrderCreateView.spec.js）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import MyProductCard from '@/components/MyProductCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { deleteProduct, getMyProducts, getProductDetail, offShelfProduct, updateProduct } from '@/api/product'
import { CODE, MY_PRODUCT_TABS, MY_PRODUCT_PAGE_SIZE } from '@/utils/constants'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const records = ref([])
const total = ref(0)
const page = ref(1)
/** 各 Tab 的数量角标 */
const counts = ref({})
/** 同步锁：正在操作的商品 id（空字符串表示空闲） */
const busyId = ref('')

const activeTab = computed(() => {
  const key = String(route.query.tab || 'all')
  return MY_PRODUCT_TABS.some((t) => t.key === key) ? key : 'all'
})
const activeStatus = computed(() => MY_PRODUCT_TABS.find((t) => t.key === activeTab.value)?.status ?? null)
const isEmpty = computed(() => !loading.value && records.value.length === 0)
const showPager = computed(() => total.value > MY_PRODUCT_PAGE_SIZE)

/** 空状态文案按 Tab 定制，避免每个 Tab 说一样的话 */
const emptyText = computed(() => {
  switch (activeTab.value) {
    case 'on':
      return { title: '还没有上架中的商品', desc: '发布并通过审核后，商品会出现在这里。' }
    case 'pending':
      return { title: '没有待审核的商品', desc: '刚发布的商品会先进入待审核，管理员通过后自动上架。' }
    case 'off':
      return { title: '没有已下架的商品', desc: '下架的商品会留在这里，随时可以重新上架。' }
    case 'soldout':
      return { title: '没有已售罄的商品', desc: '库存卖完的商品会标记为已售罄。' }
    default:
      return { title: '你还没有发布过商品', desc: '把闲置的教材、数码、生活用品挂上来，让它流动起来。' }
  }
})

// ------------------------------------------------------------------ 数据加载
async function loadList() {
  loading.value = true
  try {
    const data = await getMyProducts({
      status: activeStatus.value ?? undefined,
      page: page.value,
      size: MY_PRODUCT_PAGE_SIZE
    })
    // total 等字段是 Long→String，比较前必须 Number()
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    console.warn('[my-products] 加载失败：', error?.message)
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 5 个状态各取一次 total，拼出 Tab 角标 */
async function loadCounts() {
  const results = await Promise.all(
    MY_PRODUCT_TABS.map(async (tab) => {
      if (tab.status === null) {
        const data = await getMyProducts({ page: 1, size: 1 }).catch(() => null)
        return [tab.key, Number(data?.total ?? 0)]
      }
      const data = await getMyProducts({ status: tab.status, page: 1, size: 1 }).catch(() => null)
      return [tab.key, Number(data?.total ?? 0)]
    })
  )
  counts.value = Object.fromEntries(results)
}

async function refreshAll() {
  await Promise.all([loadList(), loadCounts()])
}

function switchTab(key) {
  if (key === activeTab.value) return
  page.value = 1
  router.replace({ name: 'product-my', query: { tab: key } })
}

function handlePageChange(next) {
  page.value = next
  loadList()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// ------------------------------------------------------------------ 操作
/** ElMessageBox 取消时 reject 的是字符串 'cancel' / 'close'，要跟真实错误区分开 */
function isDialogCancel(error) {
  return error === 'cancel' || error === 'close'
}

/** 操作类错误的统一提示 */
function handleActionError(error) {
  switch (error?.code) {
    case CODE.PRODUCT_HAS_ORDER:
      ElMessage.error('商品有未完成订单，禁止删除')
      break
    case CODE.STATUS_NOT_ALLOWED:
      ElMessage.warning('当前状态不允许该操作，已为你刷新最新状态')
      break
    case CODE.NO_PERMISSION:
      ElMessage.error('无权操作该商品')
      break
    case CODE.PRODUCT_NOT_AVAILABLE:
      ElMessage.error('商品不存在或已被删除')
      break
    default:
      ElMessage.error(error?.message || '操作失败，请稍后重试')
  }
}

function goEdit(product) {
  router.push({ name: 'product-edit', params: { id: String(product.id) } })
}

/** 下架（1→0） */
async function handleOffShelf(product) {
  if (busyId.value) return
  busyId.value = String(product.id) // 同步锁：先锁再弹窗，防止连点堆叠弹窗
  try {
    await ElMessageBox.confirm(
      `下架后买家将无法看到「${product.title}」，库存不受影响。确定下架吗？`,
      '下架商品',
      { confirmButtonText: '确认下架', cancelButtonText: '取消', type: 'warning' }
    )
    await offShelfProduct(product.id)
    ElMessage.success('商品已下架')
    await refreshAll()
  } catch (error) {
    if (!isDialogCancel(error)) handleActionError(error)
  } finally {
    busyId.value = ''
  }
}

/**
 * 重新上架（0→3）
 *
 * 后端没有「直接上架」接口：PUT /product/{id} 对 status=0 的商品会把状态重置为 3-待审核，
 * 所以「重新上架」本质是「重新提交审核」。这点在确认框里明确说清楚，避免用户以为立刻就上架了。
 * 另外 PUT 是全量更新，必须先取详情把 description / imageUrls 补齐，否则会被后端校验拦下。
 */
async function handleReList(product) {
  if (busyId.value) return
  busyId.value = String(product.id)
  try {
    await ElMessageBox.confirm(
      `「${product.title}」将重新提交管理员审核，审核通过后才会在首页展示。确定继续吗？`,
      '重新上架',
      { confirmButtonText: '确认提交', cancelButtonText: '取消', type: 'warning' }
    )

    const detail = await getProductDetail(product.id, { silent: true })
    const imageUrls =
      Array.isArray(detail?.imageUrls) && detail.imageUrls.length
        ? detail.imageUrls
        : detail?.coverImage
          ? [detail.coverImage]
          : []

    await updateProduct(
      product.id,
      {
        categoryId: detail.categoryId,
        title: detail.title,
        description: detail.description || '',
        price: detail.price,
        stock: detail.stock,
        conditionLevel: detail.conditionLevel,
        tradeType: detail.tradeType,
        tradeLocation: detail.tradeType === 2 ? '' : detail.tradeLocation || '',
        imageUrls
      },
      { silent: true }
    )

    ElMessage.success('已重新提交审核，审核通过后自动上架')
    await refreshAll()
  } catch (error) {
    if (!isDialogCancel(error)) handleActionError(error)
  } finally {
    busyId.value = ''
  }
}

/** 删除（存在未完成订单时后端返回 207） */
async function handleDelete(product) {
  if (busyId.value) return
  busyId.value = String(product.id)
  try {
    await ElMessageBox.confirm(
      `删除后「${product.title}」将从你的商品列表中消失，且不可恢复（历史订单不受影响）。确定删除吗？`,
      '删除商品',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteProduct(product.id, { silent: true })
    ElMessage.success('商品已删除')
    await refreshAll()
  } catch (error) {
    if (!isDialogCancel(error)) handleActionError(error)
  } finally {
    busyId.value = ''
  }
}

function goPublish() {
  router.push({ name: 'product-publish' })
}

// Tab 变化（含浏览器前进后退）都要重新拉列表
watch(activeTab, () => {
  page.value = 1
  loadList()
})

onMounted(refreshAll)
</script>

<template>
  <main class="my-products cm-container">
    <header class="my-products__header">
      <div>
        <h1 class="my-products__title">我的商品</h1>
        <p class="my-products__sub">管理你发布的闲置：编辑、下架、重新上架或删除</p>
      </div>
      <el-button type="primary" round :icon="Plus" @click="goPublish">发布新商品</el-button>
    </header>

    <!-- Tab：右上角带数量角标 -->
    <nav class="my-products__tabs" aria-label="商品状态筛选">
      <button
        v-for="tab in MY_PRODUCT_TABS"
        :key="tab.key"
        class="my-products__tab"
        :class="{ 'is-active': activeTab === tab.key }"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
        <span class="my-products__badge cm-num">{{ counts[tab.key] ?? 0 }}</span>
      </button>
    </nav>

    <!-- 骨架 -->
    <div v-if="loading" class="my-products__skeleton">
      <div v-for="n in 3" :key="n" class="my-products__skeleton-item">
        <el-skeleton animated>
          <template #template>
            <div style="display: flex; gap: 16px; padding: 16px">
              <el-skeleton-item variant="image" style="width: 88px; height: 88px; border-radius: 8px" />
              <div style="flex: 1">
                <el-skeleton-item variant="text" style="width: 55%" />
                <el-skeleton-item variant="text" style="width: 35%; margin-top: 12px" />
              </div>
            </div>
          </template>
        </el-skeleton>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState
      v-else-if="isEmpty"
      :title="emptyText.title"
      :description="emptyText.desc"
      action-text="去发布商品"
      @action="goPublish"
    />

    <!-- 列表 -->
    <template v-else>
      <div class="my-products__count">
        共 <b class="cm-num">{{ total }}</b> 件商品
      </div>

      <div class="my-products__items">
        <MyProductCard
          v-for="item in records"
          :key="item.id"
          :product="item"
          :busy="busyId === String(item.id)"
          @edit="goEdit"
          @off-shelf="handleOffShelf"
          @re-list="handleReList"
          @delete="handleDelete"
        />
      </div>

      <div v-if="showPager" class="my-products__pager">
        <el-pagination
          background
          layout="prev, pager, next, jumper, total"
          :total="total"
          :page-size="MY_PRODUCT_PAGE_SIZE"
          :current-page="page"
          @current-change="handlePageChange"
        />
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
.my-products {
  flex: 1;
  padding-top: 24px;
  padding-bottom: 56px;
  max-width: 960px;

  &__header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 18px;
  }

  &__title {
    font-size: 22px;
    font-weight: 800;
    color: $cm-text;
    margin-bottom: 4px;
  }

  &__sub {
    font-size: 13px;
    color: $cm-text-secondary;
  }

  // ---------------- Tab ----------------
  &__tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 18px;
  }

  &__tab {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 34px;
    padding: 0 14px;
    border: 1px solid $cm-border;
    border-radius: $cm-radius-pill;
    background: $cm-surface;
    font-size: 13px;
    font-weight: 500;
    color: $cm-text-secondary;
    cursor: pointer;
    transition:
      border-color 0.18s ease,
      color 0.18s ease,
      background 0.18s ease;

    &:hover {
      border-color: $cm-primary-light;
      color: $cm-primary-700;
    }

    &.is-active {
      border-color: $cm-primary;
      background: $cm-primary-50;
      color: $cm-primary-700;
      font-weight: 600;

      .my-products__badge {
        background: $cm-primary;
        color: #ffffff;
      }
    }
  }

  // 数量角标
  &__badge {
    min-width: 20px;
    height: 18px;
    padding: 0 6px;
    border-radius: $cm-radius-pill;
    background: $cm-hover-bg;
    color: $cm-text-secondary;
    font-size: 11px;
    font-weight: 600;
    line-height: 18px;
    text-align: center;
  }

  // ---------------- 列表 ----------------
  &__count {
    margin-bottom: 12px;
    font-size: 13px;
    color: $cm-text-secondary;

    b {
      color: $cm-primary;
      font-size: 15px;
    }
  }

  &__items {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  &__skeleton {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  &__skeleton-item {
    @include cm-card;
    overflow: hidden;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 26px;
  }

  @include cm-max($cm-bp-sm) {
    &__header {
      flex-direction: column;
      align-items: flex-start;
    }
  }
}
</style>
