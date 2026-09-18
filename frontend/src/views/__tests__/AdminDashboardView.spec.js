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

vi.mock('@/api/admin', () => ({
  getAdminStatsOverview: (...a) => getOverviewMock(...a),
  getAdminStatsOrderStatus: (...a) => getOrderStatusMock(...a),
  getAdminStatsProductCategory: (...a) => getProductCategoryMock(...a),
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
 * 挂载页面。
 *
 * @param {{overview?: object, orderStatus?: Array, category?: Array, reject?: string|null}} [options]
 *   reject：让 overview 接口失败（'forbidden' 走 code=403，其余字符串作为网络异常信息），
 *   另外两个接口照常返回数据 —— 用来验证"只要有一个 403，整页就该进无权限态"
 */
async function mountPage({
  overview = OVERVIEW,
  orderStatus = ORDER_STATUS,
  category = PRODUCT_CATEGORY,
  reject = null
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
  it('③ 两张图各自实例化 ECharts：饼图（订单状态）+ 条形图（商品分类）', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.findAllComponents(StatChart)).toHaveLength(2)
    expect(initMock).toHaveBeenCalledTimes(2)

    const pieOption = createdCharts[0].setOption.mock.calls[0][0]
    const barOption = createdCharts[1].setOption.mock.calls[0][0]

    expect(pieOption.series[0].type).toBe('pie')
    expect(barOption.series[0].type).toBe('bar')

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
    expect(createdCharts).toHaveLength(2)

    wrapper.unmount()

    expect(createdCharts[0].dispose).toHaveBeenCalledTimes(1)
    expect(createdCharts[1].dispose).toHaveBeenCalledTimes(1)
  })

  it('③ 补充：没有订单/没有商品数据时不硬画空图，改用占位文案', async () => {
    const { wrapper } = await mountPage({
      orderStatus: ORDER_STATUS.map((row) => ({ ...row, count: '0' })),
      category: [{ categoryId: '1', categoryName: '教材书籍', count: '0' }]
    })

    expect(initMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('暂无可统计的订单')
    expect(wrapper.text()).toContain('暂无可统计的商品')
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

  it('⑨ 预留区：本批不含趋势图 / 热门榜（5.5.2 才做），页面上不得出现它们的入口', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.text()).not.toContain('近 30 天')
    expect(wrapper.text()).not.toContain('热门商品')
    // 也没有导出按钮（决策 3：不做导出 CSV）
    const buttons = wrapper.findAll('button').map((b) => b.text())
    expect(buttons.some((t) => t.includes('导出'))).toBe(false)
  })
})
