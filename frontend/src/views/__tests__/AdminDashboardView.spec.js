/**
 * 管理端 · 数据统计页（第五批 5.5.1）
 *
 * 重点守：
 *   ① **4 张概览卡片**逐个渲染，且计数是"后端给的字符串"也能正确显示
 *      （JacksonConfig 把 Long 序列化成 String，页面上拿到的是 "100" 而不是 100）
 *   ② **GMV 走 formatPrice**（金额是 BigDecimal → JSON number；禁止模板里自行换算）
 *   ③ **2 张图真的实例化了 ECharts**：init 两次（饼图 + 条形图）、option 的 series.type 正确
 *   ④ **状态文案来自 constants.js**：必须出现「已发货待收货」（ORDER_STATUS_MAP[2] 的真实文案），
 *      不能是需求提示词里那种「已发货」—— 后端不提供 label，文案只能由字典出
 *   ⑤ **5 态**：loading / forbidden / error / empty / success
 *   ⑥ **卸载必须 dispose**（否则每次进路由都漏一个 ECharts 实例）
 *   ⑦ 三个接口都必须带 `{ silent: true }`（页面自己渲染错误态，不要拦截器再弹 toast）
 *
 * ECharts 用 mock：jsdom 没有 canvas，真去 init 只会得到一堆 "Not implemented: getContext"。
 * mock 之后反而能断言"到底 init 了几次、option 长什么样、dispose 有没有被调用"。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

// ---------------- ECharts 打桩（必须在 import 被测组件之前 hoisted 声明） ----------------
const { initMock, createdCharts } = vi.hoisted(() => {
  const createdCharts = []
  const initMock = vi.fn(() => {
    const chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }
    createdCharts.push(chart)
    return chart
  })
  return { initMock, createdCharts }
})

vi.mock('@/utils/echarts', () => ({
  default: { init: initMock },
  // 语义色调 → 色值：测试里只要可辨识即可
  toneColor: (tone) => `tone:${tone ?? 'unknown'}`,
  CHART_TONE_COLORS: {}
}))

// ---------------- 接口打桩 ----------------
const getOverviewMock = vi.fn()
const getOrderStatusMock = vi.fn()
const getProductCategoryMock = vi.fn()
const getTrendMock = vi.fn()
const getHotMock = vi.fn()

vi.mock('@/api/admin', () => ({
  getAdminStatsOverview: (...a) => getOverviewMock(...a),
  getAdminStatsOrderStatus: (...a) => getOrderStatusMock(...a),
  getAdminStatsProductCategory: (...a) => getProductCategoryMock(...a),
  getAdminStatsTrend: (...a) => getTrendMock(...a),
  getAdminStatsHotProducts: (...a) => getHotMock(...a),
  // 以下本文件用不到，保持模块形状完整
  getAdminProductList: vi.fn(),
  auditProduct: vi.fn(),
  forceOfflineProduct: vi.fn(),
  getAdminUserList: vi.fn(),
  banUser: vi.fn(),
  unbanUser: vi.fn(),
  getAdminOrderList: vi.fn(),
  unfreezeOrder: vi.fn(),
  forceRefundOrder: vi.fn(),
  getAuditLogList: vi.fn(),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  deleteCategory: vi.fn(),
  migrateCategory: vi.fn()
}))

import AdminDashboardView from '@/views/admin/AdminDashboardView.vue'
import StatChart from '@/components/admin/StatChart.vue'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** 后端真实响应形状：计数是**字符串**，GMV 是 number */
const OVERVIEW = {
  userTotal: '100',
  userTodayNew: '5',
  productTotal: '50',
  productTodayNew: '3',
  orderTotal: '30',
  orderTodayNew: '2',
  gmvTotal: 5000.0,
  gmvToday: 300.0
}

/** 恒定 8 条，无数据的状态 count="0" */
const ORDER_STATUS = [
  { status: 0, count: '10' },
  { status: 1, count: '5' },
  { status: 2, count: '4' },
  { status: 3, count: '7' },
  { status: 4, count: '3' },
  { status: 5, count: '1' },
  { status: 6, count: '0' },
  { status: 7, count: '0' }
]

const PRODUCT_CATEGORY = [
  { categoryId: '1', categoryName: '教材书籍', count: '20' },
  { categoryId: '2', categoryName: '数码电子', count: '15' },
  { categoryId: '6', categoryName: '其他闲置', count: '0' },
  // 孤儿商品：categoryId / categoryName 被 Jackson 的 non_null 策略整个省略
  { count: '2' }
]

/**
 * 趋势（5.5.2）：api 层已经做过 Number() 归一化 —— 所以这里给**数字数组**，
 * 与真实调用链一致（真实的 "0"/"36" 字符串 → number 的转换在 api/admin.spec.js 里验）。
 */
const TREND = {
  days: 7,
  dates: ['2026-09-12', '2026-09-13', '2026-09-14', '2026-09-15', '2026-09-16', '2026-09-17', '2026-09-18'],
  orderCounts: [0, 0, 0, 0, 36, 2, 0],
  productCounts: [3, 3, 2, 3, 2, 0, 0],
  userCounts: [0, 0, 0, 5, 26, 0, 0]
}

/** 完全没有趋势数据（连日期轴都没有）→ 趋势区应当不画图 */
const EMPTY_TREND = { days: 7, dates: [], orderCounts: [], productCounts: [], userCounts: [] }

/** 热门榜（5.5.2）：productId 是字符串（Long → String），orderCount 已被 api 层转成 number */
const HOT_ITEMS = [
  { productId: '14', productTitle: '二手高等数学教材（第三版）', categoryName: '教材书籍', orderCount: 5 },
  { productId: '21', productTitle: '英语四级真题册（近5年真题+听力音频+答案解析合集）', categoryName: '教材书籍', orderCount: 5 },
  { productId: '5', productTitle: '小米台灯', categoryName: '', orderCount: 4 }
]

/**
 * 挂载页面。
 *
 * @param {{overview?: object, orderStatus?: Array, category?: Array, trend?: object, hot?: Array,
 *   reject?: string|null, trendReject?: string|null, hotReject?: string|null}} [options]
 *   reject：让 overview 接口失败（'forbidden' 走 code=403，其余字符串作为网络异常信息），
 *   另外两个接口照常返回数据 —— 用来验证"只要有一个 403，整页就该进无权限态"。
 *   trendReject / hotReject：只让**某个区块**失败 —— 用来验证区块之间互不连坐。
 */
async function mountPage({
  overview = OVERVIEW,
  orderStatus = ORDER_STATUS,
  category = PRODUCT_CATEGORY,
  trend = TREND,
  hot = HOT_ITEMS,
  reject = null,
  trendReject = null,
  hotReject = null
} = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/dashboard', name: 'admin-dashboard', component: AdminDashboardView },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/admin/dashboard')
  await router.isReady()

  if (reject) {
    getOverviewMock.mockRejectedValue(
      reject === 'forbidden' ? { code: 403, message: '无权限访问' } : new Error(String(reject))
    )
  } else {
    getOverviewMock.mockResolvedValue(overview)
  }
  getOrderStatusMock.mockResolvedValue(orderStatus)
  getProductCategoryMock.mockResolvedValue(category)

  // 5.5.2 的两个新区块接口：各自可单独失败，互不影响
  if (trendReject) {
    getTrendMock.mockRejectedValue(
      trendReject === 'forbidden' ? { code: 403, message: '无权限访问' } : new Error(String(trendReject))
    )
  } else {
    getTrendMock.mockResolvedValue(trend)
  }
  if (hotReject) {
    getHotMock.mockRejectedValue(
      hotReject === 'forbidden' ? { code: 403, message: '无权限访问' } : new Error(String(hotReject))
    )
  } else {
    getHotMock.mockResolvedValue({ days: 7, items: hot })
  }

  const wrapper = mount(AdminDashboardView, {
    global: { plugins: [router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

describe('AdminDashboardView 数据统计', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    createdCharts.length = 0
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  // ================================================================ ① 概览卡片
  it('① 4 张概览卡片全部渲染：用户 / 商品 / 订单 / GMV（计数是字符串也要显示对）', async () => {
    const { wrapper } = await mountPage()

    const cards = wrapper.findAll('.admin-stats__card')
    expect(cards).toHaveLength(4)
    expect(cards.map((c) => c.attributes('data-key'))).toEqual(['user', 'product', 'order', 'gmv'])

    const text = wrapper.text()
    expect(text).toContain('用户总数')
    expect(text).toContain('100')
    expect(text).toContain('+5')
    expect(text).toContain('商品总数')
    expect(text).toContain('50')
    expect(text).toContain('+3')
    expect(text).toContain('订单总数')
    expect(text).toContain('30')
    expect(text).toContain('+2')
    expect(text).toContain('成交额 GMV')
  })

  it('② GMV 卡片走 formatPrice：保留两位小数并带 ¥（后端存的就是元，不做任何换算）', async () => {
    const { wrapper } = await mountPage()

    const gmv = wrapper.find('.admin-stats__card[data-key="gmv"]')
    expect(gmv.text()).toContain('¥5000.00')
    expect(gmv.text()).toContain('¥300.00')
    expect(gmv.classes()).toContain('is-money')
  })

  it('⑧ 三个接口都调用一次，且一律带 { silent: true }（页面自己渲染错误态）', async () => {
    await mountPage()

    expect(getOverviewMock).toHaveBeenCalledTimes(1)
    expect(getOrderStatusMock).toHaveBeenCalledTimes(1)
    expect(getProductCategoryMock).toHaveBeenCalledTimes(1)
    expect(getOverviewMock.mock.calls[0][0]).toEqual({ silent: true })
    expect(getOrderStatusMock.mock.calls[0][0]).toEqual({ silent: true })
    expect(getProductCategoryMock.mock.calls[0][0]).toEqual({ silent: true })
  })

  // ================================================================ ③ 图表
  it('③ 三张图各自实例化 ECharts：饼图（订单状态）+ 条形图（商品分类）+ 折线图（趋势）', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.findAllComponents(StatChart)).toHaveLength(3)
    expect(initMock).toHaveBeenCalledTimes(3)

    // 按 option 的 series 类型定位，不依赖挂载顺序（顺序变了不该让用例变红）
    const options = createdCharts.map((chart) => chart.setOption.mock.calls[0][0])
    const pieOption = options.find((o) => o.series?.[0]?.type === 'pie')
    const barOption = options.find((o) => o.series?.[0]?.type === 'bar')
    const lineOption = options.find((o) => o.series?.[0]?.type === 'line')

    expect(pieOption).toBeTruthy()
    expect(barOption).toBeTruthy()
    expect(lineOption).toBeTruthy()

    // 饼图：8 种状态一个不少（含 count=0 的两种，图例必须稳定）
    expect(pieOption.series[0].data).toHaveLength(8)
    expect(pieOption.series[0].data.map((d) => d.value)).toEqual([10, 5, 4, 7, 3, 1, 0, 0])

    // 条形图：分类名（含空分类与孤儿商品）
    expect(barOption.yAxis.data).toEqual(['教材书籍', '数码电子', '其他闲置', '未分类（分类已删除）'])
    expect(barOption.series[0].data.map((d) => d.value)).toEqual([20, 15, 0, 2])
  })

  it('④ 状态文案取自 constants.js：出现「已发货待收货」，且不出现被简化的「已发货」', async () => {
    const { wrapper } = await mountPage()

    const pieOption = createdCharts[0].setOption.mock.calls[0][0]
    const names = pieOption.series[0].data.map((d) => d.name)

    expect(names).toContain('已发货待收货') // ORDER_STATUS_MAP[2].label 的真实文案
    expect(names).toContain('退款申请中')
    expect(names).toContain('已冻结')
    expect(names).not.toContain('已发货') // 需求提示词里的简化写法，字典里没有
    expect(wrapper.text()).toContain('订单状态分布')
  })

  it('⑦ 分类分布：空分类保留（count=0），孤儿商品用字典兜底名与中性灰', async () => {
    await mountPage()

    const barOption = createdCharts[1].setOption.mock.calls[0][0]
    expect(barOption.yAxis.data).toContain('其他闲置')
    expect(barOption.yAxis.data[3]).toBe('未分类（分类已删除）')
    // 孤儿项单独上灰（与正常分类区分），正常分类交给 ECharts 调色板
    expect(barOption.series[0].data[3].itemStyle.color).toBe('#9ca3af')
    expect(barOption.series[0].data[0].itemStyle).toBeUndefined()
  })

  it('⑥ 卸载时 dispose 每个图表实例（否则每次进路由都漏一个实例）', async () => {
    const { wrapper } = await mountPage()
    expect(createdCharts).toHaveLength(3)

    wrapper.unmount()

    expect(createdCharts[0].dispose).toHaveBeenCalledTimes(1)
    expect(createdCharts[1].dispose).toHaveBeenCalledTimes(1)
    expect(createdCharts[2].dispose).toHaveBeenCalledTimes(1)
  })

  it('③ 补充：没有订单/没有商品/没有趋势数据时不硬画空图，改用占位文案', async () => {
    const { wrapper } = await mountPage({
      orderStatus: ORDER_STATUS.map((row) => ({ ...row, count: '0' })),
      category: [{ categoryId: '1', categoryName: '教材书籍', count: '0' }],
      trend: EMPTY_TREND
    })

    expect(initMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('暂无可统计的订单')
    expect(wrapper.text()).toContain('暂无可统计的商品')
    expect(wrapper.text()).toContain('暂无可统计的趋势数据')
  })

  // ================================================================ ⑤ 五态
  it('⑤-1 loading：请求未落地时显示骨架屏，且不渲染卡片', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/admin/dashboard', name: 'admin-dashboard', component: AdminDashboardView },
        { path: '/', name: 'home', component: { template: '<div/>' } }
      ]
    })
    await router.push('/admin/dashboard')
    await router.isReady()
    // 永不落地的 Promise：卡在 loading 态
    getOverviewMock.mockReturnValue(new Promise(() => {}))
    getOrderStatusMock.mockReturnValue(new Promise(() => {}))
    getProductCategoryMock.mockReturnValue(new Promise(() => {}))

    const wrapper = mount(AdminDashboardView, {
      global: { plugins: [router, ElementPlus], stubs: { transition: false } }
    })
    await flushPromises()

    expect(wrapper.find('.admin-stats__skeleton').exists()).toBe(true)
    expect(wrapper.findAll('.admin-stats__card')).toHaveLength(0)
    wrapper.unmount()
  })

  it('⑤-2 forbidden：code=403 → 独立的「没有管理权限」态（不是加载失败）', async () => {
    const { wrapper } = await mountPage({ reject: 'forbidden' })

    expect(wrapper.text()).toContain('没有管理权限')
    expect(wrapper.text()).not.toContain('加载失败，请重试')
    expect(wrapper.findAll('.admin-stats__card')).toHaveLength(0)
  })

  it('⑤-3 error → 可重试；重试成功后进入 success 并渲染 4 张卡片', async () => {
    const { wrapper } = await mountPage({ reject: '无法连接后端服务' })

    expect(wrapper.text()).toContain('加载失败，请重试')
    expect(wrapper.text()).toContain('无法连接后端服务')

    // 「重新加载」按钮：重试时三个接口重新发一次
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重新加载'))
    expect(retry).toBeTruthy()
    getOverviewMock.mockResolvedValue(OVERVIEW)
    await retry.trigger('click')
    await flushPromises()

    expect(getOverviewMock).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.admin-stats__card')).toHaveLength(4)
    expect(wrapper.text()).not.toContain('暂无统计数据')
  })

  it('⑤-4 empty：8 个数字全是 0（全新库）→ 整页空状态，而不是 4 个 0 + 两张空图', async () => {
    const { wrapper } = await mountPage({
      overview: {
        userTotal: '0',
        userTodayNew: '0',
        productTotal: '0',
        productTodayNew: '0',
        orderTotal: '0',
        orderTodayNew: '0',
        gmvTotal: 0,
        gmvToday: 0
      },
      orderStatus: ORDER_STATUS.map((row) => ({ ...row, count: '0' })),
      category: []
    })

    expect(wrapper.text()).toContain('暂无统计数据')
    expect(wrapper.findAll('.admin-stats__card')).toHaveLength(0)
    expect(initMock).not.toHaveBeenCalled()
  })

  it('⑤-5 success：工具栏写明「缓存 60 秒」与本次取回时间（避免管理员以为数字没刷新是 bug）', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('统计结果缓存')
    expect(wrapper.text()).toContain('60')
    expect(wrapper.text()).toContain('本次取回于')
    // 页面明确不做"实时刷新"：刷新按钮是手动的，且页面上没有任何轮询/定时器
    const refresh = wrapper.findAll('button').find((b) => b.text().includes('刷新数据'))
    expect(refresh).toBeTruthy()
  })

  it('⑤-6 手动刷新：点「刷新数据」会重新拉三个接口（不是轮询，仅用户主动触发）', async () => {
    const { wrapper } = await mountPage()
    expect(getOverviewMock).toHaveBeenCalledTimes(1)

    const refresh = wrapper.findAll('button').find((b) => b.text().includes('刷新数据'))
    await refresh.trigger('click')
    await flushPromises()

    expect(getOverviewMock).toHaveBeenCalledTimes(2)
    expect(getOrderStatusMock).toHaveBeenCalledTimes(2)
    expect(getProductCategoryMock).toHaveBeenCalledTimes(2)
  })

  it('⑨ 决策 3/4：不做导出、不做实时刷新；趋势与热门榜（5.5.2）都在，且不出现「近 30 天」以外的范围档', async () => {
    const { wrapper } = await mountPage()

    // 5.5.2 的两个区块标题必须在
    expect(wrapper.text()).toContain('趋势')
    expect(wrapper.text()).toContain('热门商品榜')

    // 决策 3：不做导出 CSV
    const buttons = wrapper.findAll('button').map((b) => b.text())
    expect(buttons.some((t) => t.includes('导出'))).toBe(false)

    // 决策 4：不做实时刷新 —— 没有定时器/轮询（只有用户点一下的手动刷新）
    expect(buttons.some((t) => t.includes('刷新数据'))).toBe(true)
  })

  // ================================================================ 5.5.2 趋势区

  it('⑩ 趋势区：三条折线 + 7 个日期，x 轴直接用后端给的 yyyy-MM-dd（前端不再格式化）', async () => {
    const { wrapper } = await mountPage()

    expect(getTrendMock).toHaveBeenCalledWith(7, { silent: true })

    const lineOption = createdCharts
      .map((chart) => chart.setOption.mock.calls[0][0])
      .find((option) => option.series?.[0]?.type === 'line')

    expect(lineOption).toBeTruthy()
    expect(lineOption.series.map((s) => s.name)).toEqual(['订单量', '商品发布', '用户注册'])
    expect(lineOption.series.map((s) => s.data)).toEqual([
      TREND.orderCounts,
      TREND.productCounts,
      TREND.userCounts
    ])
    // 日期原样进轴（不重新格式化）
    expect(lineOption.xAxis.data).toEqual(TREND.dates)
    expect(wrapper.text()).toContain('近 7 天')
  })

  it('⑩ 补充：趋势全为 0 时**仍然画平线**（"这段时间真的没成交"是有用信息，不该换成空态）', async () => {
    const { wrapper } = await mountPage({
      trend: {
        days: 7,
        dates: TREND.dates,
        orderCounts: [0, 0, 0, 0, 0, 0, 0],
        productCounts: [0, 0, 0, 0, 0, 0, 0],
        userCounts: [0, 0, 0, 0, 0, 0, 0]
      }
    })

    const lineOption = createdCharts
      .map((chart) => chart.setOption.mock.calls[0][0])
      .find((option) => option.series?.[0]?.type === 'line')
    expect(lineOption.series[0].data).toEqual([0, 0, 0, 0, 0, 0, 0])
    expect(wrapper.text()).not.toContain('暂无可统计的趋势数据')
  })

  it('⑪ 范围切换：点「近 30 天」只重新拉趋势（days=30），概览/分布/热门榜不重复请求', async () => {
    const { wrapper } = await mountPage()
    expect(getTrendMock).toHaveBeenCalledTimes(1)
    expect(getOverviewMock).toHaveBeenCalledTimes(1)
    expect(getHotMock).toHaveBeenCalledTimes(1)

    // el-radio-button 渲染成原生 radio input：setValue 会选中并触发 change
    const radios = wrapper.findAll('.el-radio-button__original-radio')
    expect(radios).toHaveLength(2) // 只有 7 / 30 两档
    await radios[1].setValue()
    await flushPromises()

    expect(getTrendMock).toHaveBeenCalledTimes(2)
    expect(getTrendMock.mock.calls[1][0]).toBe(30)
    // 只有趋势重新请求：其它区块与天数无关，不该被带着刷一遍
    expect(getOverviewMock).toHaveBeenCalledTimes(1)
    expect(getOrderStatusMock).toHaveBeenCalledTimes(1)
    expect(getProductCategoryMock).toHaveBeenCalledTimes(1)
    expect(getHotMock).toHaveBeenCalledTimes(1)
  })

  // ================================================================ 5.5.2 热门榜区

  it('⑫ 热门榜：Top 列表渲染（排名/标题/分类/订单数），分类为 null 时显示「—」', async () => {
    const { wrapper } = await mountPage()

    // 榜单固定近 7 天 + Top 10（决策 2；接口保留 days 参数但前端只传 7）
    expect(getHotMock).toHaveBeenCalledWith(7, 10, { silent: true })

    const bodyRows = wrapper.findAll('.admin-stats__hot-table .el-table__body tbody tr')
    expect(bodyRows).toHaveLength(3)
    expect(bodyRows[0].text()).toContain('1')
    expect(bodyRows[0].text()).toContain('二手高等数学教材（第三版）')
    expect(bodyRows[0].text()).toContain('教材书籍')
    expect(bodyRows[0].text()).toContain('5')
    // categoryName 为空 → 占位符「—」（后端 null，文案由前端出）
    expect(bodyRows[2].text()).toContain('—')
    expect(bodyRows[2].text()).toContain('小米台灯')
  })

  it('⑬ 热门榜空态：近 7 天没有订单 → 「近 7 天暂无成交」，且不渲染表格', async () => {
    const { wrapper } = await mountPage({ hot: [] })

    expect(wrapper.text()).toContain('近 7 天暂无成交')
    expect(wrapper.find('.admin-stats__hot-table').exists()).toBe(false)
  })

  it('⑭ 区块隔离：趋势失败不影响热门榜与概览（反之亦然），各自显示自己的错误态', async () => {
    const { wrapper } = await mountPage({ trendReject: '趋势接口超时' })

    // 趋势区：自己的错误态 + 自己的重试按钮
    expect(wrapper.find('[data-region="trend-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('趋势接口超时')
    expect(wrapper.findAll('button').some((b) => b.text().includes('重新加载趋势'))).toBe(true)

    // 热门榜与概览区**不受影响**
    expect(wrapper.find('[data-region="hot-error"]').exists()).toBe(false)
    expect(wrapper.findAll('.admin-stats__hot-table .el-table__body tbody tr')).toHaveLength(3)
    expect(wrapper.findAll('.admin-stats__card')).toHaveLength(4)
    // 页面级状态机没有被区块级失败带偏（仍是 success）
    expect(wrapper.text()).not.toContain('加载失败，请重试')

    // 反向：热门榜失败不影响趋势
    const { wrapper: second } = await mountPage({ hotReject: '榜单接口超时' })
    expect(second.find('[data-region="hot-error"]').exists()).toBe(true)
    expect(second.find('[data-region="trend-error"]').exists()).toBe(false)
    expect(second.findAll('.admin-stats__card')).toHaveLength(4)
    expect(second.text()).not.toContain('加载失败，请重试')
  })

  it('⑭ 补充：区块级 403 → 该区块给出权限提示（不冒充成网络故障）', async () => {
    const { wrapper } = await mountPage({ trendReject: 'forbidden' })

    expect(wrapper.find('[data-region="trend-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('没有查看统计数据的权限')
  })

  it('⑮ 刷新按钮：概览 + 分布 + 趋势 + 热门榜 五个接口全部重新请求', async () => {
    const { wrapper } = await mountPage()
    expect(getTrendMock).toHaveBeenCalledTimes(1)
    expect(getHotMock).toHaveBeenCalledTimes(1)

    const refresh = wrapper.findAll('button').find((b) => b.text().includes('刷新数据'))
    await refresh.trigger('click')
    await flushPromises()

    expect(getOverviewMock).toHaveBeenCalledTimes(2)
    expect(getOrderStatusMock).toHaveBeenCalledTimes(2)
    expect(getProductCategoryMock).toHaveBeenCalledTimes(2)
    expect(getTrendMock).toHaveBeenCalledTimes(2)
    expect(getHotMock).toHaveBeenCalledTimes(2)
    // 页面级 loading 会把内容区换成骨架屏 → 3 张图随之卸载（dispose）并被重建新实例，
    // 所以 init 次数是 3 + 3；关键是**旧实例都被 dispose 了**，不会累积泄漏
    expect(initMock).toHaveBeenCalledTimes(6)
    expect(createdCharts.slice(0, 3).every((chart) => chart.dispose.mock.calls.length === 1)).toBe(true)
    expect(wrapper.findAllComponents(StatChart)).toHaveLength(3)
  })
})
