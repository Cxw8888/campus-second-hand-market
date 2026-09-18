/**
 * 统计图表的 option 构造函数（纯函数，批次 5.5.1）
 *
 * 为什么把 option 从 StatChart.vue 里抽出来：
 *   1. **可验证**：option 是纯数据，能在 Node 里离线构造出来，配合 ECharts 的
 *      SSR（`renderer: 'svg' + ssr: true`）做一次真实渲染 —— 这样"按需引入的组件够不够"
 *      就不再靠肉眼猜（漏注册时 ECharts 运行时只会在浏览器控制台抛
 *      `Component xxx not exists`，构建期完全不报错）；
 *   2. **可单测**：断言配色、图例顺序、空数据行为都不必挂载组件、更不必有 canvas；
 *   3. 组件本身只剩"init / setOption / resize / dispose"四件事，薄得像张纸。
 *
 * 这里**不含任何业务文案**：分类名、状态名、tooltip 文案一律由调用方传进来
 * （状态名来自 constants.js 的 ORDER_STATUS_MAP）。
 */
import { toneColor } from '@/utils/echarts'

/**
 * 单项颜色：优先显式 color，其次语义 tone 查色表，都没有则返回 undefined
 * （返回 undefined 时 ECharts 用自带调色板，不会渲染成黑色）。
 */
function itemColor(item) {
  if (item?.color) return item.color
  if (item?.tone) return toneColor(item.tone)
  return undefined
}

/** 饼图 option（环形） */
function buildPieOption(data) {
  return {
    tooltip: { trigger: 'item' },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { fontSize: 12, color: '#6b7280' }
    },
    series: [
      {
        type: 'pie',
        radius: ['46%', '68%'],
        center: ['50%', '44%'],
        // 标签重叠时自动让位（PieChart 内部实现，无需额外注册 LabelLayout）
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { fontSize: 11, color: '#6b7280', formatter: '{b}\n{c}' },
        labelLine: { length: 8, length2: 8, lineStyle: { color: '#e5e7eb' } },
        emphasis: { scale: true, scaleSize: 6 },
        data: (data ?? []).map((item) => ({
          name: item.name,
          value: item.value,
          // 逐项上色：色值来自 constants.js 字典的 tone（绿=已完成、灰=已取消…）
          itemStyle: { color: itemColor(item) }
        }))
      }
    ]
  }
}

/** 条形图 option（横向；分类名较长，横向比纵向好读） */
function buildBarOption(data) {
  return {
    // ⚠️ ECharts 6 把 grid.containLabel 标成了 deprecated（实测会打警告：
    //    "Specified `grid.containLabel` but no `use(LegacyGridContainLabel)`"），
    //    且在没注册那个 legacy feature 时**静默失效** → 分类名会被裁掉。
    //    v6 的等价写法是 outerBoundsMode/outerBoundsContain：
    //      outerBoundsMode: 'same'          —— 外边界 = grid.left/right/top/bottom 围出的矩形
    //      outerBoundsContain: 'all'        —— 保证"矩形 + 轴标签 + 轴名"都塞得进去
    //    （官方文档：containLabel:true 等价于 {outerBoundsMode:'same', outerBoundsContain:'axisLabel'}）
    //    保留 right: 40 是给条形末端的数值标签留位置 —— 那不属于 outerBounds 的管辖范围。
    grid: {
      left: 8,
      right: 40,
      top: 12,
      bottom: 8,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all'
    },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'value',
      // 商品数是整数：不加 minInterval 会出现 0.5 件这种刻度
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#9ca3af', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f3f4f6' } }
    },
    yAxis: {
      type: 'category',
      // inverse：第一个分类排在最上面（与页面上的列表顺序一致）
      inverse: true,
      data: (data ?? []).map((item) => item.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#6b7280', fontSize: 12 }
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 18,
        itemStyle: { color: '#10b981', borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', formatter: '{c}', color: '#6b7280', fontSize: 11 },
        data: (data ?? []).map((item) => {
          const color = itemColor(item)
          // 单独指定了颜色/tone 就用它（例如「未分类」用中性灰，与正常分类区分开）
          return { value: item.value, itemStyle: color ? { color } : undefined }
        })
      }
    ]
  }
}

/**
 * 构造 option。
 *
 * @param {{ type?: 'pie'|'bar', data?: Array<{name: string, value: number, tone?: string, color?: string}>,
 *   options?: object }} params
 * @returns {object} ECharts option（顶层键可被 options 覆盖）
 */
export function buildStatChartOption({ type = 'pie', data = [], options = {} } = {}) {
  const base = type === 'bar' ? buildBarOption(data) : buildPieOption(data)
  // 浅合并：options 覆盖顶层键（series / tooltip / legend / grid 都能整体替换）
  return { ...base, ...(options ?? {}) }
}

export default buildStatChartOption
