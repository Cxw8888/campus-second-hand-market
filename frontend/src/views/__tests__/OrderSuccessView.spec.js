/**
 * 下单成功页：待支付文案与倒计时按订单 trade_type 分档（批次 6.0.7）
 *
 * 背景：后端 6.0.6 起待支付窗口分档（面交 120 分钟 / 邮寄 15 分钟），而本页原来写死
 * "请在 15 分钟内完成支付"与"超过 15 分钟未支付" ⇒ 面交单被前端误报为即将/已被取消。
 *
 * 数据来源：本页 onMounted 就用 orderId 调 GET /order/detail/{id}，
 * 而该接口返回的 OrderVO **带 tradeType** —— 所以不需要新增请求，读 order.tradeType 即可。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

const getOrderDetailMock = vi.fn()
const payOrderMock = vi.fn()

vi.mock('@/api/order', () => ({
  getOrderDetail: (...a) => getOrderDetailMock(...a),
  payOrder: (...a) => payOrderMock(...a),
  getOrderToken: vi.fn(),
  createOrder: vi.fn(),
  getOrderList: vi.fn(),
  cancelOrder: vi.fn(),
  shipOrder: vi.fn(),
  receiveOrder: vi.fn(),
  finishFaceOrder: vi.fn(),
  finishFaceBySellerOrder: vi.fn(),
  applyRefund: vi.fn(),
  agreeRefund: vi.fn(),
  rejectRefund: vi.fn()
}))

import OrderSuccessView from '@/views/OrderSuccessView.vue'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** 固定"现在"，让倒计时的起始值可断言 */
const NOW = new Date('2026/09/19 12:00:00').getTime()

function order(status, tradeType, createTime = '2026-09-19 12:00:00') {
  return {
    id: '99',
    orderNo: '358478719859429376',
    productTitle: '考研数学复习全书 九成新',
    amount: 45,
    status,
    tradeType,
    createTime
  }
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/order/success/:orderId', name: 'order-success', component: OrderSuccessView },
      { path: '/order/detail/:orderId', name: 'order-detail', component: { template: '<div/>' } },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/order/success/99')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  const wrapper = mount(OrderSuccessView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return wrapper
}

describe('OrderSuccessView · 待支付窗口按 trade_type 分档', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('① 面交单（tradeType=1）→ 文案 120 分钟，倒计时从 120:00 开始', async () => {
    getOrderDetailMock.mockResolvedValue(order(0, 1))

    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('请在 120 分钟内完成支付')
    expect(wrapper.text()).not.toContain('请在 15 分钟内完成支付')
    expect(wrapper.find('.pay-countdown').text()).toContain('120:00')
    wrapper.unmount()
  })

  it('② 邮寄单（tradeType=2）→ 文案 15 分钟，倒计时从 15:00 开始', async () => {
    getOrderDetailMock.mockResolvedValue(order(0, 2))

    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('请在 15 分钟内完成支付')
    expect(wrapper.find('.pay-countdown').text()).toContain('15:00')
    wrapper.unmount()
  })

  it('③ 皆可单（tradeType=3）→ 与邮寄同档（后端快照就是 3，按 IN (2,3) 走 15 分钟）', async () => {
    getOrderDetailMock.mockResolvedValue(order(0, 3))

    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('请在 15 分钟内完成支付')
    expect(wrapper.find('.pay-countdown').text()).toContain('15:00')
    wrapper.unmount()
  })

  it('④ ★回归：面交单下单 20 分钟后仍显示"剩余"，不出现"已超过支付时限"', async () => {
    getOrderDetailMock.mockResolvedValue(order(0, 1, '2026-09-19 11:40:00'))

    const wrapper = await mountPage()

    // 120 分钟窗口 − 已过 20 分钟 = 剩余 100 分钟
    expect(wrapper.find('.pay-countdown').text()).toContain('100:00')
    expect(wrapper.text()).not.toContain('已超过支付时限')
    wrapper.unmount()
  })

  it('⑤ 邮寄单下单 20 分钟后确实已超时（同规则的另一半）', async () => {
    getOrderDetailMock.mockResolvedValue(order(0, 2, '2026-09-19 11:40:00'))

    const wrapper = await mountPage()

    expect(wrapper.find('.pay-countdown').text()).toContain('已超过支付时限')
    wrapper.unmount()
  })

  it('⑥ 已取消（status=4）面交单 → 提示"超过 120 分钟未支付，库存已回补"', async () => {
    getOrderDetailMock.mockResolvedValue(order(4, 1))

    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('订单已自动取消')
    expect(wrapper.text()).toContain('超过 120 分钟未支付，库存已回补')
    expect(wrapper.find('.pay-countdown').exists()).toBe(false)
    wrapper.unmount()
  })

  it('⑦ 已取消（status=4）邮寄单 → 提示"超过 15 分钟未支付，库存已回补"', async () => {
    getOrderDetailMock.mockResolvedValue(order(4, 2))

    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('超过 15 分钟未支付，库存已回补')
    wrapper.unmount()
  })

  it('⑧ 非待支付状态不再显示倒计时，hint 用状态字典文案（与窗口无关）', async () => {
    getOrderDetailMock.mockResolvedValue(order(1, 1))

    const wrapper = await mountPage()

    expect(wrapper.find('.pay-countdown').exists()).toBe(false)
    expect(wrapper.text()).toContain('等待卖家发货或约定面交')
    wrapper.unmount()
  })
})
