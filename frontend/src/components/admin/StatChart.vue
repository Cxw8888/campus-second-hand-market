<script setup>
/**
 * 统计图表容器（批次 5.5.1）
 *
 * 只负责四件事：init / setOption / resize / dispose。
 * option 的构造在 `statChartOptions.js`（纯函数，可离线 SSR 验证、可单测），
 * 所以本组件里**没有**任何业务文案与配色逻辑 —— 这样它才是可复用组件，
 * 而不是又一个写死了中文的图表。
 *
 * 两个必须守住的点（都踩过坑）：
 *   ① **实例不能放进 ref/reactive**：ECharts 实例被 Vue 的 Proxy 包一层之后，
 *      内部 DOM/canvas 引用会出各种诡异问题（resize 失效、dispose 报错）。
 *      这里用模块内的普通变量持有它。
 *   ② **卸载时必须 dispose()**：ECharts 会往 window 上挂事件、往 canvas 上绑上下文，
 *      不 dispose 就会随每次进入路由累积。
 *
 * props：
 *   · type     'pie' | 'bar' | 'line'
 *   · data     [{ name, value, tone?, color? }] —— pie / bar 用
 *   · dates    ['2026-09-12', ...] —— line 的 x 轴（后端序列化好的 yyyy-MM-dd，不再格式化）
 *   · series   [{ name, data: number[], tone? }] —— line 的多条折线
 *   · options  顶层覆盖项（浅合并），例如 tooltip.formatter 这类业务文案
 *   · height   容器高度（图表容器必须有确定高度，否则 canvas 高度为 0 → 白屏）
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import echarts from '@/utils/echarts'
import { buildStatChartOption, buildTrendLineOption } from '@/components/admin/statChartOptions'

const props = defineProps({
  /** 图表类型 */
  type: { type: String, default: 'pie' },
  /** 数据：[{ name, value, tone, color }]（pie / bar） */
  data: { type: Array, default: () => [] },
  /** x 轴日期（line），如 ['2026-09-12', ...] */
  dates: { type: Array, default: () => [] },
  /** 多条折线（line）：[{ name, data: number[], tone }] */
  series: { type: Array, default: () => [] },
  /** 顶层覆盖项（浅合并进默认 option） */
  options: { type: Object, default: () => ({}) },
  /** 容器高度 */
  height: { type: String, default: '300px' }
})

const containerRef = ref(null)

/** ECharts 实例：普通变量，**故意不放进 ref**（见文件头注释 ①） */
let chart = null

/**
 * 无数据：不初始化实例。
 *
 * ECharts 对空数据会画出一片空白坐标系（页面上看起来像"图表坏了"），
 * 所以空态一律由页面用 v-if 换成占位说明，组件本身不渲染半成品。
 *
 * ⚠️ 折线图的判据是「有没有日期轴」，不是「计数是不是全 0」：
 *    近 7 天一笔订单都没有时，一条贴着 0 的平线是**有意义的信息**
 *    （说明这段时间真的没成交），比换成"暂无数据"更准确。
 */
const isEmpty = () => {
  if (props.type === 'line') {
    return !Array.isArray(props.dates) || props.dates.length === 0
  }
  return !Array.isArray(props.data) || props.data.length === 0
}

function buildOption() {
  if (props.type === 'line') {
    return buildTrendLineOption({ dates: props.dates, series: props.series, options: props.options })
  }
  return buildStatChartOption({ type: props.type, data: props.data, options: props.options })
}

/** 渲染（notMerge=true 重画：不然被删掉的分片会残留在图上） */
function render() {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

/** 容器尺寸变化（窗口缩放 / 侧栏折叠）时重算画布 —— 不 resize 会糊成一片 */
function handleResize() {
  chart?.resize()
}

function createChart() {
  if (chart || !containerRef.value || isEmpty()) return
  chart = echarts.init(containerRef.value)
  window.addEventListener('resize', handleResize)
  render()
}

onMounted(createChart)

watch(
  () => [props.data, props.dates, props.series, props.type, props.options],
  () => {
    // 数据从「空 → 有」时容器才第一次出现（v-if 控制），需要补一次 init
    createChart()
    render()
  },
  { deep: true }
)

onUnmounted(() => {
  // ⚠️ 顺序：先摘事件监听，再销毁实例（dispose 之后再 resize 会抛错）
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})

/** 暴露给测试：option 是纯函数产物，直接断言它比去翻 canvas 稳得多 */
defineExpose({ buildOption })

/** 无障碍描述：折线图按"共几天"说，饼图/条形图按"共几项"说 */
const ariaLabel = computed(() => {
  if (props.type === 'line') return `折线图：共 ${props.dates.length} 天`
  return `${props.type === 'bar' ? '条形图' : '饼图'}：共 ${props.data.length} 项`
})
</script>

<template>
  <div
    ref="containerRef"
    class="stat-chart"
    :style="{ height }"
    role="img"
    :aria-label="ariaLabel"
  />
</template>

<style scoped lang="scss">
.stat-chart {
  width: 100%;
  // 容器必须有确定高度：canvas 高度为 0 时 ECharts 会画出空白并只在控制台给一句 warning
  min-height: 220px;
}
</style>
