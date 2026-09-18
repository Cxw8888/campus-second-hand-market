// @vitest-environment node
/**
 * 统计图表 option 与「按需引入清单是否够用」（批次 5.5.1）
 *
 * ⚠️ 本文件**刻意跑在 node 环境**（见上一行的 docblock）：SSR 渲染不需要 DOM，
 * 而在 jsdom 下 zrender 会去调 `canvas.getContext('2d')` 量文字宽度，
 * jsdom 没实现该 API，会刷一屏 "Not implemented: HTMLCanvasElement.prototype.getContext"。
 * 用 node 环境既贴合 ECharts 官方的服务端渲染姿势，也让输出干净。
 *
 * 这组测试回答两个问题：
 *
 *   ① **option 构造对不对**：饼图 8 个分片、配色取自字典 tone、条形图空分类保留、
 *      孤儿分类单独上灰、options 能覆盖顶层键 —— 纯函数，断言直接、不用挂载组件。
 *
 *   ② **按需引入的组件够不够**（本批最容易翻车的地方）：
 *      ECharts 的组件是 `echarts.use([...])` 手工注册的，**漏注册时构建期完全正常**，
 *      只在运行时往控制台抛一句 `Component xxx not exists` —— 而我们的验证环境没有
 *      真实浏览器 canvas。于是这里用 ECharts 官方支持的 **SSR 渲染**（SVG + 无 DOM）
 *      把真实的 option 画一遍：只要注册清单缺东西，setOption / 渲染就会抛，
 *      用例立刻变红，不会等到答辩现场才发现饼图是空白。
 *
 *      ⚠️ 两点说明（避免误解）：
 *        · SSR 需要 SVG 渲染器，所以本文件**额外**注册了 SVGRenderer；
 *          生产代码（src/utils/echarts.js）只注册 CanvasRenderer（浏览器里更省）。
 *          即：本用例验证的是**图表与组件**的注册，渲染器差异不影响结论。
 *        · 真实的 canvas 像素渲染只能在浏览器里验证，见报告「五、没能验证的部分」。
 */
import { describe, it, expect, vi } from 'vitest'
// 注意：这里必须取**默认导出**（echarts/core 的命名空间对象，带 use/init），
// `import * as echarts` 拿到的是本模块自己的命名空间，没有 use 方法。
import echarts, { toneColor } from '@/utils/echarts'
import { buildStatChartOption, buildTrendLineOption } from '@/components/admin/statChartOptions'
import { SVGRenderer } from 'echarts/renderers'

// SSR 渲染必须用 SVG 渲染器（jsdom 没有 canvas，CanvasRenderer 在这里根本没法工作）
echarts.use([SVGRenderer])

/** 复用页面真实的数据形状：状态名来自 constants.js 字典，这里直接给成渲染好的名字 */
const PIE_DATA = [
  { name: '待支付', value: 10, tone: 'orange' },
  { name: '已支付待发货', value: 5, tone: 'blue' },
  { name: '已完成', value: 7, tone: 'green' },
  { name: '已取消', value: 3, tone: 'gray' },
  { name: '退款申请中', value: 0, tone: 'yellow' }
]

const BAR_DATA = [
  { name: '教材书籍', value: 20 },
  { name: '其他闲置', value: 0 },
  { name: '未分类（分类已删除）', value: 2, color: '#9ca3af' }
]

/** 趋势图数据（批次 5.5.2）：日期是后端序列化好的 yyyy-MM-dd 字符串 */
const TREND_DATES = [
  '2026-09-12',
  '2026-09-13',
  '2026-09-14',
  '2026-09-15',
  '2026-09-16',
  '2026-09-17',
  '2026-09-18'
]

const TREND_SERIES = [
  { name: '订单量', data: [0, 0, 0, 0, 36, 2, 0], tone: 'orange' },
  { name: '商品发布', data: [3, 3, 2, 3, 2, 0, 0], tone: 'green' },
  { name: '用户注册', data: [0, 0, 0, 5, 26, 0, 0], tone: 'blue' }
]

/**
 * 无 DOM 的 SSR 渲染（ECharts 官方用法：init(null, null, { ssr: true })）。
 *
 * 顺带把 ECharts 自己的日志**全部拦下来并断言为空**：漏注册组件时它抛的是异常，
 * 但 deprecated 写法（例如 ECharts 6 的 grid.containLabel）只打一句 console 警告、
 * 功能静默降级 —— 那种问题在浏览器里几乎没人会注意到，必须由用例兜住。
 */
function renderSvg(option) {
  const logs = []
  const spies = ['log', 'warn', 'error'].map((level) =>
    vi.spyOn(console, level).mockImplementation((...args) => {
      logs.push(String(args[0]))
    })
  )
  try {
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 480, height: 300 })
    chart.setOption(option)
    const svg = chart.renderToSVGString()
    chart.dispose()
    return { svg, logs }
  } finally {
    spies.forEach((spy) => spy.mockRestore())
  }
}

describe('statChartOptions 图表 option', () => {
  it('① 饼图：分片数 = 数据条数，颜色逐一取自字典 tone（含 count=0 的分片也保留）', () => {
    const option = buildStatChartOption({ type: 'pie', data: PIE_DATA })

    expect(option.series[0].type).toBe('pie')
    expect(option.series[0].data.map((d) => d.name)).toEqual([
      '待支付',
      '已支付待发货',
      '已完成',
      '已取消',
      '退款申请中'
    ])
    expect(option.series[0].data.map((d) => d.value)).toEqual([10, 5, 7, 3, 0])
    // 配色链路：tone → utils/echarts.js 的语义色表
    expect(option.series[0].data[0].itemStyle.color).toBe(toneColor('orange'))
    expect(option.series[0].data[2].itemStyle.color).toBe('#10b981')
    expect(option.series[0].data[3].itemStyle.color).toBe(toneColor('gray'))
    // 饼图必须有 legend（8 种状态靠它认全）
    expect(option.legend.bottom).toBe(0)
  })

  it('② 条形图：分类顺序与名称进 yAxis，空分类保留，孤儿分类单独上灰', () => {
    const option = buildStatChartOption({ type: 'bar', data: BAR_DATA })

    expect(option.series[0].type).toBe('bar')
    expect(option.yAxis.data).toEqual(['教材书籍', '其他闲置', '未分类（分类已删除）'])
    expect(option.series[0].data.map((d) => d.value)).toEqual([20, 0, 2])
    // 未指定颜色的项交给 ECharts 调色板（itemStyle 为 undefined），孤儿项固定灰色
    expect(option.series[0].data[0].itemStyle).toBeUndefined()
    expect(option.series[0].data[2].itemStyle.color).toBe('#9ca3af')
    // 整数刻度：不加 minInterval 会出现 0.5 件
    expect(option.xAxis.minInterval).toBe(1)
  })

  it('③ options 覆盖顶层键（tooltip.formatter 由页面注入，组件里不写业务中文）', () => {
    const formatter = (params) => `${params.name}: ${params.value}`
    const option = buildStatChartOption({
      type: 'bar',
      data: BAR_DATA,
      options: { tooltip: { trigger: 'axis', formatter } }
    })

    expect(option.tooltip.formatter).toBe(formatter)
    // 未覆盖的键保持默认
    expect(option.grid).toBeTruthy()
  })

  it('④ 边界：空数据不抛异常（页面会用 v-if 换成占位文案，组件不渲染半成品）', () => {
    expect(buildStatChartOption({ type: 'pie', data: [] }).series[0].data).toEqual([])
    expect(buildStatChartOption({ type: 'bar', data: [] }).yAxis.data).toEqual([])
    expect(buildStatChartOption({}).series[0].type).toBe('pie') // 默认类型
    expect(buildStatChartOption()).toBeTruthy()
  })

  it('⑤ toneColor：未知 tone 兜底为中性灰，不返回 undefined', () => {
    expect(toneColor('green')).toBe('#10b981')
    expect(toneColor('不存在的色调')).toBe('#9ca3af')
    expect(toneColor(undefined)).toBe('#9ca3af')
  })

  it('⑥ SSR 真实渲染：注册清单足够 —— 饼图能画出分片与图例（缺组件会在这里直接抛）', () => {
    const option = buildStatChartOption({
      type: 'pie',
      data: PIE_DATA,
      options: { tooltip: { trigger: 'item', formatter: '{b} {c}' } }
    })

    const { svg, logs } = renderSvg(option)

    expect(svg.startsWith('<svg')).toBe(true)
    expect(svg.length).toBeGreaterThan(500)
    // 图例文字必须在 SVG 里（说明 LegendComponent 已注册）
    expect(svg).toContain('已完成')
    expect(svg).toContain('退款申请中')
    // 分片路径（说明 PieChart 已注册）
    expect(svg).toContain('<path')
    // ECharts 自己一声不吭才算真的干净（警告 = 有组件没注册或写法已废弃）
    expect(logs.filter((line) => line.includes('[ECharts]'))).toEqual([])
  })

  it('⑦ SSR 真实渲染：注册清单足够 —— 条形图能画出坐标轴与分类名（缺 GridComponent 会抛）', () => {
    const option = buildStatChartOption({
      type: 'bar',
      data: BAR_DATA,
      options: { tooltip: { trigger: 'axis', formatter: '{b} {c}' } }
    })

    const { svg, logs } = renderSvg(option)

    expect(svg.startsWith('<svg')).toBe(true)
    // 分类名进 yAxis 轴标签，说明直角坐标系 + 轴组件都到位了
    expect(svg).toContain('教材书籍')
    expect(svg).toContain('未分类（分类已删除）')
    expect(svg).toContain('<rect')
    expect(logs.filter((line) => line.includes('[ECharts]'))).toEqual([])
  })

  it('⑧ 反向保护：故意漏掉组件的 option 必须报错 —— 证明上一条用例真的在把关', () => {
    // 用 SVGRenderer 之外的另一种"缺注册"手法：给 series 一个没注册过的图表类型。
    // 若 ECharts 对未知类型一声不吭，那么用例⑥⑦就是假绿，这里专门堵住这个可能。
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 200, height: 120 })
    const logs = []
    const errorSpy = vi.spyOn(console, 'error').mockImplementation((...args) => logs.push(String(args[0])))
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation((...args) => logs.push(String(args[0])))
    try {
      chart.setOption({ series: [{ type: '不存在的图表类型', data: [1, 2, 3] }] })
    } catch (error) {
      logs.push(String(error?.message))
    } finally {
      errorSpy.mockRestore()
      warnSpy.mockRestore()
      chart.dispose()
    }

    expect(logs.join(' ')).toMatch(/not exists|Unknown|不存在/i)
  })

  // ================================================================ 趋势折线图（5.5.2）

  it('⑨ 折线图：三条线、x 轴用后端给的日期字符串（不再格式化）、grid 用 v6 的 outerBounds', () => {
    const option = buildTrendLineOption({ dates: TREND_DATES, series: TREND_SERIES })

    expect(option.series).toHaveLength(3)
    expect(option.series.map((s) => s.type)).toEqual(['line', 'line', 'line'])
    expect(option.series.map((s) => s.name)).toEqual(['订单量', '商品发布', '用户注册'])
    expect(option.series[0].data).toEqual([0, 0, 0, 0, 36, 2, 0])
    // 日期原样进 x 轴：前端绝不重新格式化（后端已序列化成 yyyy-MM-dd）
    expect(option.xAxis.data).toEqual(TREND_DATES)
    expect(option.xAxis.type).toBe('category')
    expect(option.yAxis.minInterval).toBe(1)
    // ECharts 6：containLabel 已废弃（未注册 legacy feature 时会静默失效），必须用 outerBounds*
    expect(option.grid.outerBoundsMode).toBe('same')
    expect(option.grid.outerBoundsContain).toBe('all')
    expect(option.grid.containLabel).toBeUndefined()
    // 颜色来自语义 tone
    expect(option.color).toEqual([toneColor('orange'), toneColor('green'), toneColor('blue')])
  })

  it('⑩ 折线图：30 天时隐藏数据点标记（否则 30 个圆点会把线糊住），7 天时显示', () => {
    const many = new Array(30).fill(1)
    const longOption = buildTrendLineOption({
      dates: many.map((_, i) => `2026-09-${String(i + 1).padStart(2, '0')}`),
      series: [{ name: '订单量', data: many, tone: 'orange' }]
    })
    expect(longOption.series[0].showSymbol).toBe(false)

    const shortOption = buildTrendLineOption({ dates: TREND_DATES, series: TREND_SERIES })
    expect(shortOption.series[0].showSymbol).toBe(true)
  })

  it('⑪ 折线图 options 覆盖：tooltip.formatter 由页面注入（组件里不写业务中文）', () => {
    const formatter = (params) => `自定义 ${params.length}`
    const option = buildTrendLineOption({
      dates: TREND_DATES,
      series: TREND_SERIES,
      options: { tooltip: { trigger: 'axis', formatter } }
    })

    expect(option.tooltip.formatter).toBe(formatter)
    expect(option.legend).toBeTruthy() // 未覆盖的键保持默认
  })

  it('⑫ 折线图边界：空日期 / 空 series 不抛异常（页面用 v-if 换成占位文案）', () => {
    const option = buildTrendLineOption({ dates: [], series: [] })
    expect(option.xAxis.data).toEqual([])
    expect(option.series).toEqual([])
    expect(buildTrendLineOption()).toBeTruthy()
  })

  it('⑬ SSR 真实渲染（5.5.2）：折线图三条线都画出来，且 ECharts 零警告（含废弃 API 检查）', () => {
    const option = buildTrendLineOption({
      dates: TREND_DATES,
      series: TREND_SERIES,
      options: { tooltip: { trigger: 'axis', formatter: '{b} {c}' } }
    })

    const { svg, logs } = renderSvg(option)

    expect(svg.startsWith('<svg')).toBe(true)
    // 图例 = 三条线的名字（LegendComponent 必须已注册）
    expect(svg).toContain('订单量')
    expect(svg).toContain('商品发布')
    expect(svg).toContain('用户注册')
    // 折线路径（LineChart 必须已注册）
    expect(svg).toContain('<path')
    // 关键：没有 "Component xxx not exists"，也没有 "grid.containLabel is deprecated"
    expect(logs.filter((line) => line.includes('[ECharts]'))).toEqual([])
  })
})
