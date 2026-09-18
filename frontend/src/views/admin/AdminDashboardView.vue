<script setup>
/**
 * 管理端 · 数据统计（/admin/dashboard，批次 5.5.1）
 *
 * 数据源（三个都是只读接口，见 AdminStatsController.java）：
 *   · GET /api/v1/admin/stats/overview          → 概览 8 个数字
 *   · GET /api/v1/admin/stats/order-status      → 订单状态分布，恒定 8 条
 *   · GET /api/v1/admin/stats/product-category  → 商品分类分布（含空分类与孤儿商品）
 *
 * 三个必须记住的后端事实（都写进了 api/admin.js 的注释里，这里再点一遍）：
 *   ① **Long 序列化成字符串** → 计数是 "100"，展示前要 Number()（计数不是 id，可以转）
 *   ② **60 秒缓存且无主动失效** → 页面顶部必须写明"数据可能滞后"，别让人以为是 bug
 *   ③ **403 是 HTTP 200 + body.code=403** → 单独一态，提示语与出口都和"加载失败"不同
 *
 * 本批只做「概览区 + 分布区」；5.5.2 的趋势图与热门榜直接追加在下面的
 * 【预留】注释处即可，布局（section 之间互相独立）已经留好位置。
 *
 * 状态机：loading → success / empty / error / forbidden
 *   （每个分支都必然可达；finally 一定会把 loading 复位）
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Goods, List, Money, Refresh, User } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import StatChart from '@/components/admin/StatChart.vue'
import {
  getAdminStatsOrderStatus,
  getAdminStatsOverview,
  getAdminStatsProductCategory
} from '@/api/admin'
import {
  ADMIN_STATS_CACHE_SECONDS,
  ADMIN_STATS_UNCATEGORIZED_LABEL,
  CODE,
  orderStatusLabel,
  orderStatusTone
} from '@/utils/constants'
import { formatPrice } from '@/utils/format'

const router = useRouter()

const loading = ref(true)
const forbidden = ref(false)
const errorMessage = ref('')

const overview = ref(null)
const statusRows = ref([])
const categoryRows = ref([])
/** 本次数据的取回时刻（页面上标出来，配合"缓存 60 秒"的说明一起看） */
const loadedAt = ref('')

/**
 * 后端 Long → 字符串（JacksonConfig 的 ToStringSerializer），展示/绘图前统一转数字。
 *
 * 注意与「禁止 Number(id)」不冲突：那条规则针对的是 id / orderNo 这类**标识**
 * （超过 2^53 会丢精度），而计数是安全的小整数，且 ECharts 只接受 number。
 */
function toCount(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

function nowText() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 概览 4 张卡片：数值全部先转成**展示字符串**，模板里不再做任何运算 */
const cards = computed(() => {
  const o = overview.value ?? {}
  return [
    {
      key: 'user',
      label: '用户总数',
      icon: User,
      valueText: String(toCount(o.userTotal)),
      unit: '人',
      todayLabel: '今日新增',
      todayText: `+${toCount(o.userTodayNew)}`,
      money: false
    },
    {
      key: 'product',
      label: '商品总数',
      icon: Goods,
      valueText: String(toCount(o.productTotal)),
      unit: '件',
      todayLabel: '今日发布',
      todayText: `+${toCount(o.productTodayNew)}`,
      money: false
    },
    {
      key: 'order',
      label: '订单总数',
      icon: List,
      valueText: String(toCount(o.orderTotal)),
      unit: '笔',
      todayLabel: '今日新增',
      todayText: `+${toCount(o.orderTodayNew)}`,
      money: false
    },
    {
      key: 'gmv',
      label: '成交额 GMV',
      icon: Money,
      // 金额一律走 formatPrice（后端存的就是元，禁止任何 /100 之类的自行换算）
      valueText: `¥${formatPrice(o.gmvTotal)}`,
      unit: '',
      todayLabel: '今日成交',
      todayText: `¥${formatPrice(o.gmvToday)}`,
      money: true
    }
  ]
})

/**
 * 饼图数据：**不滤掉 0**。
 *
 * 8 种状态恒定出现（后端保证返回 8 条），这样图例是稳定的 —— 管理员扫一眼就知道
 * "退款申请中 0" 是当下真的没有，而不是"这个状态不存在"。
 * 状态名与配色一律走 constants.js 字典（orderStatusLabel / orderStatusTone），
 * 后端不提供 label（也就在后端硬编码不了中文，两边不会长歪）。
 */
const statusChartData = computed(() =>
  statusRows.value.map((row) => ({
    name: orderStatusLabel(row.status),
    value: toCount(row.count),
    tone: orderStatusTone(row.status)
  }))
)

/** 分类条形图数据；孤儿商品（categoryId 缺失）用中性灰单独标出来 */
const categoryChartData = computed(() =>
  categoryRows.value.map((row) => ({
    name: row.categoryName || ADMIN_STATS_UNCATEGORIZED_LABEL,
    value: toCount(row.count),
    color: row.categoryId == null ? '#9ca3af' : undefined
  }))
)

/** 订单一张都没有时，饼图会是一片空白 → 换成占位文案（图例全 0 很容易被误读成"图坏了"） */
const hasStatusData = computed(() => statusChartData.value.some((item) => item.value > 0))
/** 商品一件都没有时同理（注意：空分类 count=0 是正常数据，不算"没数据"） */
const hasCategoryData = computed(() => categoryChartData.value.some((item) => item.value > 0))

/** tooltip 文案在页面这层给（StatChart 保持通用，不写死业务中文） */
const statusChartOptions = computed(() => ({
  tooltip: {
    trigger: 'item',
    formatter: (params) => `${params.name}<br/>订单 ${params.value} 笔（${params.percent}%）`
  }
}))

const categoryChartOptions = computed(() => ({
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (params) => {
      const item = Array.isArray(params) ? params[0] : params
      return `${item.name}<br/>商品 ${item.value} 件`
    }
  }
}))

/** 页面级空态：8 个数字全是 0（全新库）—— 这时整页显示空状态，而不是 4 个 0 + 2 张空图 */
const isTotallyEmpty = computed(() => {
  const o = overview.value
  if (!o) return false
  return [
    o.userTotal,
    o.userTodayNew,
    o.productTotal,
    o.productTodayNew,
    o.orderTotal,
    o.orderTodayNew,
    o.gmvTotal,
    o.gmvToday
  ].every((value) => toCount(value) === 0)
})

const isEmpty = computed(
  () => !loading.value && !errorMessage.value && !forbidden.value && isTotallyEmpty.value
)

// ------------------------------------------------------------------ 加载
async function fetchAll() {
  loading.value = true
  errorMessage.value = ''
  forbidden.value = false
  try {
    // 三个接口互不依赖，并发取；任何一个失败都进错误态（页面是一个整体，不做半残渲染）
    const [overviewData, statusData, categoryData] = await Promise.all([
      getAdminStatsOverview({ silent: true }),
      getAdminStatsOrderStatus({ silent: true }),
      getAdminStatsProductCategory({ silent: true })
    ])
    overview.value = overviewData ?? null
    statusRows.value = Array.isArray(statusData) ? statusData : []
    categoryRows.value = Array.isArray(categoryData) ? categoryData : []
    loadedAt.value = nowText()
  } catch (error) {
    console.warn('[admin-stats] 统计数据加载失败：', error?.message)
    overview.value = null
    statusRows.value = []
    categoryRows.value = []
    if (error?.code === CODE.FORBIDDEN) {
      // 权限可能中途被收回（管理员被降权）：这一态和"加载失败"完全不同，不能混
      forbidden.value = true
      return
    }
    errorMessage.value = error?.message || '网络异常或服务不可用'
  } finally {
    loading.value = false
  }
}

function goHome() {
  router.push({ name: 'home' })
}

onMounted(fetchAll)
</script>

<template>
  <section class="admin-stats">
    <!-- ---------------- 工具栏 ---------------- -->
    <div class="admin-stats__bar">
      <p class="admin-stats__hint">
        统计结果缓存 <b class="cm-num">{{ ADMIN_STATS_CACHE_SECONDS }}</b> 秒
        <template v-if="loadedAt">· 本次取回于 <span class="cm-num">{{ loadedAt }}</span></template>
      </p>
      <el-button plain :icon="Refresh" :loading="loading" @click="fetchAll">刷新数据</el-button>
    </div>

    <!-- ---------------- ① loading ---------------- -->
    <div v-if="loading" class="admin-stats__skeleton">
      <el-skeleton :rows="8" animated />
    </div>

    <!-- ---------------- ② 无权限（403） ---------------- -->
    <EmptyState
      v-else-if="forbidden"
      title="没有管理权限"
      description="当前账号不是管理员（后端返回 code=403）。如果你确实需要查看统计数据，请用管理员账号重新登录。"
      action-text="返回首页"
      @action="goHome"
    />

    <!-- ---------------- ③ 加载失败 ---------------- -->
    <EmptyState
      v-else-if="errorMessage"
      title="加载失败，请重试"
      :description="errorMessage"
      action-text="重新加载"
      @action="fetchAll"
    />

    <!-- ---------------- ④ 全空（全新库，8 个数字都是 0） ---------------- -->
    <EmptyState
      v-else-if="isEmpty"
      title="暂无统计数据"
      description="平台还没有任何用户、商品与订单，等有数据后这里会自动出现卡片与分布图。"
      action-text="重新加载"
      @action="fetchAll"
    />

    <!-- ---------------- ⑤ success ---------------- -->
    <template v-else>
      <!-- 概览区：4 卡片一行 -->
      <div class="admin-stats__cards">
        <article
          v-for="card in cards"
          :key="card.key"
          class="admin-stats__card"
          :class="{ 'is-money': card.money }"
          :data-key="card.key"
        >
          <div class="admin-stats__card-head">
            <span class="admin-stats__card-icon">
              <el-icon :size="16"><component :is="card.icon" /></el-icon>
            </span>
            <span class="admin-stats__card-label">{{ card.label }}</span>
          </div>

          <p class="admin-stats__card-value cm-num">
            {{ card.valueText }}<em v-if="card.unit">{{ card.unit }}</em>
          </p>

          <p class="admin-stats__card-foot">
            <span class="admin-stats__card-today">{{ card.todayLabel }}</span>
            <b class="cm-num" :class="{ 'admin-stats__card-today-num': true }">{{ card.todayText }}</b>
          </p>
        </article>
      </div>

      <!-- 分布区：2 图一行 -->
      <div class="admin-stats__charts">
        <section class="admin-stats__panel" data-chart="order-status">
          <header class="admin-stats__panel-head">
            <h2 class="admin-stats__panel-title">订单状态分布</h2>
            <span class="admin-stats__panel-tip">8 种状态全覆盖（含 0 单状态）</span>
          </header>

          <StatChart
            v-if="hasStatusData"
            type="pie"
            :data="statusChartData"
            :options="statusChartOptions"
            height="300px"
          />
          <p v-else class="admin-stats__placeholder">暂无可统计的订单。</p>
        </section>

        <section class="admin-stats__panel" data-chart="product-category">
          <header class="admin-stats__panel-head">
            <h2 class="admin-stats__panel-title">商品分类分布</h2>
            <span class="admin-stats__panel-tip">含空分类；灰色为分类已删除的孤儿商品</span>
          </header>

          <StatChart
            v-if="hasCategoryData"
            type="bar"
            :data="categoryChartData"
            :options="categoryChartOptions"
            height="300px"
          />
          <p v-else class="admin-stats__placeholder">暂无可统计的商品。</p>
        </section>
      </div>

      <!--
        【预留】趋势区（5.5.2）：趋势范围 7 天 / 30 天可切换，折线图
        【预留】热门榜区（5.5.2）：热门商品 / 活跃用户排行
        两处都按上面「分布区」的写法追加即可：<section class="admin-stats__panel"> + StatChart。
        趋势图需要 LineChart 与 DataZoom/时间轴，届时在 src/utils/echarts.js 的 use([...]) 里补注册。
      -->
    </template>
  </section>
</template>

<style scoped lang="scss">
.admin-stats {
  // ---------------- 工具栏 ----------------
  &__bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 14px;
  }

  &__hint {
    font-size: 12.5px;
    color: $cm-text-secondary;

    b {
      color: $cm-text;
    }
  }

  &__skeleton {
    @include cm-card;
    padding: 20px;
  }

  // ---------------- 概览卡片 ----------------
  &__cards {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 16px;

    @include cm-max($cm-bp-md) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  &__card {
    @include cm-card;
    padding: 16px 18px 14px;
    border-top: 3px solid $cm-primary;

    &.is-money {
      border-top-color: $cm-accent;
    }
  }

  &__card-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
  }

  &__card-icon {
    width: 26px;
    height: 26px;
    border-radius: $cm-radius-sm;
    background: $cm-primary-50;
    color: $cm-primary-700;
    @include cm-center;

    .is-money & {
      background: $cm-accent-50;
      color: $cm-accent-dark;
    }
  }

  &__card-label {
    font-size: 13px;
    font-weight: 600;
    color: $cm-text-secondary;
  }

  &__card-value {
    @include cm-price(26px);
    color: $cm-text;

    em {
      font-size: 12px;
      font-style: normal;
      font-weight: 500;
      color: $cm-text-placeholder;
      margin-left: 4px;
    }

    .is-money & {
      color: $cm-accent;
    }
  }

  &__card-foot {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 8px;
    font-size: 12px;
  }

  &__card-today {
    color: $cm-text-placeholder;
  }

  &__card-today-num {
    color: $cm-primary-700;

    .is-money & {
      color: $cm-accent-dark;
    }
  }

  // ---------------- 分布图 ----------------
  &__charts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;

    @include cm-max($cm-bp-md) {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  &__panel {
    @include cm-card;
    padding: 16px 18px 18px;
    min-width: 0;
  }

  &__panel-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 6px;
  }

  &__panel-title {
    font-size: 14.5px;
    font-weight: 700;
    color: $cm-text;
  }

  &__panel-tip {
    font-size: 11.5px;
    color: $cm-text-placeholder;
  }

  &__placeholder {
    padding: 70px 0;
    text-align: center;
    font-size: 13px;
    color: $cm-text-placeholder;
  }
}
</style>
