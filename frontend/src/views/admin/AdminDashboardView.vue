<script setup>
/**
 * 管理端 · 数据统计（/admin/dashboard，批次 5.5.1 概览/分布 + 5.5.2 趋势/热门榜）
 *
 * 数据源（五个都是只读接口，见 AdminStatsController.java）：
 *   · GET /api/v1/admin/stats/overview          → 概览 8 个数字
 *   · GET /api/v1/admin/stats/order-status      → 订单状态分布，恒定 8 条
 *   · GET /api/v1/admin/stats/product-category  → 商品分类分布（含空分类与孤儿商品）
 *   · GET /api/v1/admin/stats/trend?days=7|30   → 趋势（同一时间轴上的三条序列）
 *   · GET /api/v1/admin/stats/hot-products      → 热门商品榜（近 7 天按订单数）
 *
 * 必须记住的后端事实（api/admin.js 里也写了，这里再点一遍）：
 *   ① **Long 序列化成字符串** → 计数/订单数是 "100"，api 层已统一 Number() 归一化
 *   ② **60 秒缓存且无主动失效** → 顶部统一写明"数据可能滞后"，别让人以为是 bug
 *   ③ **403 是 HTTP 200 + body.code=403** → 单独一态，提示语与出口都和"加载失败"不同
 *
 * 状态机（5.5.2 起是**两层**）：
 *   · 页面级 5 态（loading / success / empty / error / forbidden）——
 *     仍由 5.5.1 的三个接口决定，行为不变；
 *   · 区块级状态：趋势区与热门榜区**各自** loading / error / 内容，互不影响 ——
 *     某一个接口挂了不会连坐另一个（这是 5.5.2 的硬性要求）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Goods, List, Money, Refresh, User } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import StatChart from '@/components/admin/StatChart.vue'
import {
  getAdminStatsHotProducts,
  getAdminStatsOrderStatus,
  getAdminStatsOverview,
  getAdminStatsProductCategory,
  getAdminStatsTrend
} from '@/api/admin'
import {
  ADMIN_STATS_CACHE_SECONDS,
  ADMIN_STATS_EMPTY_CELL,
  ADMIN_STATS_HOT_DAYS,
  ADMIN_STATS_HOT_LIMIT,
  ADMIN_STATS_TREND_RANGES,
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

// ---------------- 趋势区状态（5.5.2，独立成态） ----------------
/** 当前趋势范围（7 / 30），默认 7；与后端 ALLOWED_WINDOW_DAYS 白名单对齐 */
const trendDays = ref(ADMIN_STATS_TREND_RANGES[0])
const trendLoading = ref(true)
const trendError = ref('')
const trend = ref(null)

// ---------------- 热门榜区状态（5.5.2，独立成态） ----------------
const hotLoading = ref(true)
const hotError = ref('')
const hotItems = ref([])

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

// ------------------------------------------------------------------ 趋势区（5.5.2）
/** x 轴日期：后端已序列化成 yyyy-MM-dd，**前端不再格式化**，直接喂 ECharts */
const trendDates = computed(() => trend.value?.dates ?? [])
const hasTrendData = computed(() => trendDates.value.length > 0)

/**
 * 三条折线：订单量 / 商品发布 / 用户注册。
 *
 * 颜色语义与全站一致（橙=交易、绿=商品、蓝=成长），都从 utils/echarts.js 的
 * 语义色表取，保证"同一个语义在任何页面都是同一个颜色"。
 * 计数已由 api 层 Number() 归一化，这里不再做任何转换。
 */
const trendSeries = computed(() => [
  { name: '订单量', data: trend.value?.orderCounts ?? [], tone: 'orange' },
  { name: '商品发布', data: trend.value?.productCounts ?? [], tone: 'green' },
  { name: '用户注册', data: trend.value?.userCounts ?? [], tone: 'blue' }
])

/** tooltip 文案在页面这层给（StatChart / option 纯函数里不写业务中文） */
const trendChartOptions = computed(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: (params) => {
      const list = Array.isArray(params) ? params : [params]
      const head = list[0]?.axisValue ?? ''
      return [head, ...list.map((p) => `${p.marker}${p.seriesName}：${p.value}`)].join('<br/>')
    }
  }
}))

// ------------------------------------------------------------------ 热门榜区（5.5.2）
/** 排名徽标样式：前三名高亮（其余保持中性） */
function rankClass(index) {
  if (index === 0) return 'is-top1'
  if (index === 1) return 'is-top2'
  if (index === 2) return 'is-top3'
  return ''
}

/** 空单元格占位：后端返回 null（商品已删除 / 未分类 / 分类已删除）时显示「—」 */
function cellText(value) {
  return value ? String(value) : ADMIN_STATS_EMPTY_CELL
}

/**
 * 标题太长时才挂 tooltip。
 *
 * 短标题也挂 tooltip 会在鼠标划过时弹出一个和眼前文字一模一样的气泡，纯噪音；
 * 阈值取 18 个字（约等于本页表格列宽能完整显示的字数）。
 */
function isLongTitle(title) {
  return String(title ?? '').length > 18
}

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

// ------------------------------------------------------------------ 区块级加载（5.5.2）
/**
 * 趋势区：自己 loading、自己 error，**不影响页面其它区块**。
 *
 * 切换 7/30 天时只调这一个接口：后端缓存 Key 带 days 维度
 * （admin:stats:trend:7 / admin:stats:trend:30），天然隔离，
 * 前端**不需要**手动清缓存（5.4.5 的教训只适用于"多实例共享 Redis 做 A/B 对比"那种场景）。
 */
async function loadTrend() {
  trendLoading.value = true
  trendError.value = ''
  try {
    trend.value = await getAdminStatsTrend(trendDays.value, { silent: true })
  } catch (error) {
    console.warn('[admin-stats] 趋势加载失败：', error?.message)
    trend.value = null
    trendError.value = regionErrorText(error)
  } finally {
    trendLoading.value = false
  }
}

/** 热门榜区：同样独立成态；本批固定近 7 天（决策 2），页面上不做切换 */
async function loadHotProducts() {
  hotLoading.value = true
  hotError.value = ''
  try {
    const data = await getAdminStatsHotProducts(ADMIN_STATS_HOT_DAYS, ADMIN_STATS_HOT_LIMIT, { silent: true })
    hotItems.value = data.items
  } catch (error) {
    console.warn('[admin-stats] 热门榜加载失败：', error?.message)
    hotItems.value = []
    hotError.value = regionErrorText(error)
  } finally {
    hotLoading.value = false
  }
}

/** 区块级错误文案：403 是权限问题（和网络故障分开说），其余原样透出后端/网络信息 */
function regionErrorText(error) {
  if (error?.code === CODE.FORBIDDEN) {
    return '当前账号没有查看统计数据的权限（code=403）'
  }
  return error?.message || '网络异常或服务不可用'
}

/** 切换趋势范围：只重取趋势区（概览/分布/热门榜与天数无关） */
function changeTrendRange(days) {
  if (days === trendDays.value) return
  trendDays.value = days
  loadTrend()
}

/**
 * 统一刷新：概览 + 分布（页面级三接口） + 趋势 + 热门榜 **全部重取**（5.5.2 要求）。
 *
 * 页面级 loading 仍只由 5.5.1 的三个接口驱动（不改既有状态机），
 * 刷新按钮的 loading 则由 {@link refreshing} 汇总四路状态。
 */
function refreshAll() {
  fetchAll()
  loadTrend()
  loadHotProducts()
}

/** 刷新按钮的 loading：四路任意一路在跑都显示 loading */
const refreshing = computed(() => loading.value || trendLoading.value || hotLoading.value)

onMounted(refreshAll)
</script>

<template>
  <section class="admin-stats">
    <!-- ---------------- 工具栏 ---------------- -->
    <div class="admin-stats__bar">
      <p class="admin-stats__hint">
        统计结果缓存 <b class="cm-num">{{ ADMIN_STATS_CACHE_SECONDS }}</b> 秒
        <template v-if="loadedAt">· 本次取回于 <span class="cm-num">{{ loadedAt }}</span></template>
      </p>
      <el-button plain :icon="Refresh" :loading="refreshing" @click="refreshAll">刷新数据</el-button>
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
      @action="refreshAll"
    />

    <!-- ---------------- ④ 全空（全新库，8 个数字都是 0） ---------------- -->
    <EmptyState
      v-else-if="isEmpty"
      title="暂无统计数据"
      description="平台还没有任何用户、商品与订单，等有数据后这里会自动出现卡片与分布图。"
      action-text="重新加载"
      @action="refreshAll"
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
        趋势区 + 热门榜区（5.5.2）。
        两块**各自** loading / error / 内容：任何一个接口失败都不会连坐另一个，
        也不会把已经画好的概览区与分布区打回骨架屏。
      -->
      <div class="admin-stats__stack">
        <!-- 趋势区：3 条折线 + 7 / 30 天切换 -->
        <section class="admin-stats__panel" data-chart="trend">
          <header class="admin-stats__panel-head">
            <h2 class="admin-stats__panel-title">趋势</h2>
            <div class="admin-stats__panel-actions">
              <span class="admin-stats__panel-tip">按天统计（GMT+8）</span>
              <el-radio-group :model-value="trendDays" size="small" @change="changeTrendRange">
                <el-radio-button
                  v-for="range in ADMIN_STATS_TREND_RANGES"
                  :key="range"
                  :value="range"
                >
                  近 {{ range }} 天
                </el-radio-button>
              </el-radio-group>
            </div>
          </header>

          <div v-if="trendLoading" class="admin-stats__region-skeleton" data-region="trend-loading">
            <el-skeleton :rows="4" animated />
          </div>

          <div v-else-if="trendError" class="admin-stats__region-error" data-region="trend-error">
            <p class="admin-stats__region-text">{{ trendError }}</p>
            <el-button plain size="small" @click="loadTrend">重新加载趋势</el-button>
          </div>

          <StatChart
            v-else-if="hasTrendData"
            type="line"
            :dates="trendDates"
            :series="trendSeries"
            :options="trendChartOptions"
            height="280px"
          />
          <p v-else class="admin-stats__placeholder">暂无可统计的趋势数据。</p>
        </section>

        <!-- 热门榜区：Top N（本批固定近 7 天，不做切换） -->
        <section class="admin-stats__panel" data-chart="hot-products">
          <header class="admin-stats__panel-head">
            <h2 class="admin-stats__panel-title">热门商品榜</h2>
            <span class="admin-stats__panel-tip">
              近 {{ ADMIN_STATS_HOT_DAYS }} 天 · 按订单数（Top {{ ADMIN_STATS_HOT_LIMIT }}）
            </span>
          </header>

          <div v-if="hotLoading" class="admin-stats__region-skeleton" data-region="hot-loading">
            <el-skeleton :rows="5" animated />
          </div>

          <div v-else-if="hotError" class="admin-stats__region-error" data-region="hot-error">
            <p class="admin-stats__region-text">{{ hotError }}</p>
            <el-button plain size="small" @click="loadHotProducts">重新加载榜单</el-button>
          </div>

          <el-table
            v-else-if="hotItems.length"
            :data="hotItems"
            row-key="productId"
            class="admin-stats__hot-table"
          >
            <el-table-column label="排名" width="72" align="center">
              <template #default="{ $index }">
                <span class="admin-stats__rank" :class="rankClass($index)">{{ $index + 1 }}</span>
              </template>
            </el-table-column>

            <el-table-column label="商品标题" min-width="260">
              <template #default="{ row }">
                <!-- 标题过长才挂 tooltip（短标题挂上去只是噪音） -->
                <el-tooltip
                  :content="row.productTitle"
                  placement="top"
                  :disabled="!isLongTitle(row.productTitle)"
                >
                  <span class="admin-stats__hot-title">{{ row.productTitle }}</span>
                </el-tooltip>
              </template>
            </el-table-column>

            <el-table-column label="分类" width="150">
              <template #default="{ row }">
                <span class="admin-stats__hot-category">{{ cellText(row.categoryName) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="订单数" width="110" align="right">
              <template #default="{ row }">
                <b class="cm-num admin-stats__hot-count">{{ row.orderCount }}</b>
              </template>
            </el-table-column>
          </el-table>

          <p v-else class="admin-stats__placeholder">
            近 {{ ADMIN_STATS_HOT_DAYS }} 天暂无成交。
          </p>
        </section>
      </div>
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

  // ---------------- 趋势区 / 热门榜区（5.5.2，整行堆叠） ----------------
  &__stack {
    display: grid;
    // 趋势图与 Top10 表格都需要横向空间，故整行堆叠而不是并排（并排会把列挤窄）
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
    margin-top: 16px;
  }

  &__panel-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__region-skeleton {
    padding: 10px 0 0;
  }

  &__region-error {
    @include cm-center;
    flex-direction: column;
    gap: 10px;
    padding: 48px 0;
    text-align: center;
  }

  &__region-text {
    font-size: 13px;
    color: $cm-text-secondary;
    line-height: 1.7;
    max-width: 420px;
  }

  &__hot-table {
    margin-top: 6px;

    :deep(.el-table__header th) {
      background: $cm-gray-tag-50;
      color: $cm-text;
      font-weight: 700;
    }
  }

  &__hot-title {
    display: inline-block;
    max-width: 100%;
    font-size: 13.5px;
    color: $cm-text;
    @include cm-ellipsis;
  }

  &__hot-category {
    font-size: 13px;
    color: $cm-text-secondary;
  }

  &__hot-count {
    color: $cm-accent;
  }

  &__rank {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    font-size: 12px;
    font-weight: 700;
    background: $cm-gray-tag-50;
    color: $cm-text-secondary;

    // 前三名给一点点金属色（金/银/铜），只是视觉引导，不引入新语义
    &.is-top1 {
      background: #fef3c7;
      color: #b45309;
    }

    &.is-top2 {
      background: #e5e7eb;
      color: #4b5563;
    }

    &.is-top3 {
      background: #ffedd5;
      color: #c2410c;
    }
  }
}
</style>
