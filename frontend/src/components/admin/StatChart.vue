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
 *   · type     'pie' | 'bar'
 *   · data     [{ name, value, tone? , color? }]
 *   · options  顶层覆盖项（浅合并），例如 tooltip.formatter 这类业务文案
 *   · height   容器高度（图表容器必须有确定高度，否则 canvas 高度为 0 → 白屏）
 */
import { onMounted, onUnmounted, ref, watch } from 'vue'
import echarts from '@/utils/echarts'
import { buildStatChartOption } from '@/components/admin/statChartOptions'

const props = defineProps({
  /** 图表类型 */
  type: { type: String, default: 'pie' },
  /** 数据：[{ name, value, tone, color }] */
  data: { type: Array, default: () => [] },
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
 */
const isEmpty = () => !Array.isArray(props.data) || props.data.length === 0

function buildOption() {
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
  () => [props.data, props.type, props.options],
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
</script>

<template>
  <div
    ref="containerRef"
    class="stat-chart"
    :style="{ height }"
    role="img"
    :aria-label="`${type === 'bar' ? '条形图' : '饼图'}：共 ${data.length} 项`"
  />
</template>

<style scoped lang="scss">
.stat-chart {
  width: 100%;
  // 容器必须有确定高度：canvas 高度为 0 时 ECharts 会画出空白并只在控制台给一句 warning
  min-height: 220px;
}
</style>
