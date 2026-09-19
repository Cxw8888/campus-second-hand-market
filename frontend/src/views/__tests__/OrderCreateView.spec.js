/**
 * 回归测试：下单页「提交订单」的防连点
 *
 * 背景（真实 Bug）：submitting 原本在「确认弹窗之后」才置 true，
 * 于是从点击到弹窗出现这段时间按钮仍可点，快速连点会堆叠出多个确认对话框。
 *
 * 修复的关键不是「加了 :loading」，而是**上锁必须同步**：
 * 第一版把 submitting=true 提到弹窗之前，但后面还留着 `await formRef.validate()`，
 * 因为 await 会让出执行权，5 次点击仍会在任何一次置位之前全部通过入口守卫 ——
 * 实测依然是 5 个弹窗（这个用例当时就是红的，才发现了问题）。
 *
 * 所以本文件里「连点 5 次只弹 1 个弹窗」这一条是真正有价值的回归防线：
 * 谁要是把上锁时机挪到 await 后面，这里立刻会红。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

// ---------------- 接口打桩 ----------------
const productDetail = vi.fn()
const createOrderMock = vi.fn()
const getOrderTokenMock = vi.fn()

vi.mock('@/api/product', () => ({
  getProductDetail: (...args) => productDetail(...args)
}))

vi.mock('@/api/order', () => ({
  getOrderToken: (...args) => getOrderTokenMock(...args),
  createOrder: (...args) => createOrderMock(...args),
  // 下面这些 OrderCreateView 用不到，但保持模块形状完整
  getOrderList: vi.fn(),
  getOrderDetail: vi.fn(),
  payOrder: vi.fn(),
  cancelOrder: vi.fn(),
  shipOrder: vi.fn(),
  receiveOrder: vi.fn(),
  finishFaceOrder: vi.fn(),
  applyRefund: vi.fn(),
  agreeRefund: vi.fn(),
  rejectRefund: vi.fn()
}))

import OrderCreateView from '@/views/OrderCreateView.vue'

/** jsdom 没有 ResizeObserver，Element Plus 的输入组件会用到 */
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** 统计当前 DOM 里有多少个 MessageBox */
function dialogCount() {
  return document.querySelectorAll('.el-message-box').length
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/order/create', name: 'order-create', component: OrderCreateView },
      { path: '/order/success/:orderId', name: 'order-success', component: { template: '<div/>' } },
      { path: '/', name: 'home', component: { template: '<div/>' } },
      { path: '/login', name: 'login', component: { template: '<div/>' } }
    ]
  })
  await router.push('/order/create?productId=30&token=test-token')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  const wrapper = mount(OrderCreateView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return wrapper
}

describe('OrderCreateView 提交订单防连点', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()

    productDetail.mockResolvedValue({
      id: '30',
      title: '考研数学复习全书 九成新',
      price: 45,
      stock: 3,
      conditionLevel: 2,
      tradeType: 1,
      tradeLocation: '图书馆一楼大厅',
      coverImage: '',
      imageUrls: [],
      status: 1,
      sellerId: '26',
      sellerNickname: '数院小周',
      createTime: '2026-09-16 16:03:43'
    })
    getOrderTokenMock.mockResolvedValue({ token: 'fresh-token', expireSeconds: 300 })
    createOrderMock.mockResolvedValue({ orderId: '99', orderNo: '123', amount: 45, status: 0 })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('快速连点 5 次「提交订单」，只弹出 1 个确认对话框', async () => {
    const wrapper = await mountPage()

    const submitBtn = wrapper.find('.order-create__submit')
    expect(submitBtn.exists()).toBe(true)

    // 模拟用户狂点：连点 5 次，中间不做任何等待
    for (let i = 0; i < 5; i++) {
      await submitBtn.trigger('click')
    }
    await flushPromises()

    expect(dialogCount()).toBe(1)

    // 按钮应已进入 loading（Element Plus 的 loading 态同时会禁用点击）
    expect(
      submitBtn.classes().some((c) => c.includes('is-loading') || c.includes('is-disabled'))
    ).toBe(true)

    // 此时还没确认，不应该发出下单请求
    expect(createOrderMock).not.toHaveBeenCalled()
  })

  it('确认后只发 1 次下单请求', async () => {
    const wrapper = await mountPage()

    const submitBtn = wrapper.find('.order-create__submit')
    await submitBtn.trigger('click')
    await flushPromises()

    // 点击弹窗的「确认下单」
    const confirmBtn = document.querySelector('.el-message-box__btns .el-button--primary')
    expect(confirmBtn).toBeTruthy()
    confirmBtn.click()
    await flushPromises()
    await flushPromises()

    expect(createOrderMock).toHaveBeenCalledTimes(1)

    // 再点一次也不该再提交（页面此时已跳转，但守卫仍生效）
    await submitBtn.trigger('click')
    await flushPromises()
    expect(createOrderMock).toHaveBeenCalledTimes(1)
  })

  it('返回 202（重复提交）→ 提示「这单已经提交过了」并刷新防重 Token、解锁按钮', async () => {
    createOrderMock.mockRejectedValueOnce({ code: 202, message: '请勿重复提交' })

    const wrapper = await mountPage()
    const submitBtn = wrapper.find('.order-create__submit')

    await submitBtn.trigger('click')
    await flushPromises()
    document.querySelector('.el-message-box__btns .el-button--primary').click()
    await flushPromises()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 60)) // 等 ElMessage 渲染

    const msgText = document.querySelector('.el-message')?.textContent || ''
    expect(msgText).toContain('这单已经提交过了')

    // 202 之后必须重新取一个 Token，否则用户再点还是 202
    expect(getOrderTokenMock).toHaveBeenCalledTimes(1)

    // 失败后按钮必须解锁，允许用户重试
    expect(submitBtn.classes()).not.toContain('is-loading')

    // 再点一次应该能正常弹出确认框（而不是被锁死）
    await submitBtn.trigger('click')
    await flushPromises()
    expect(dialogCount()).toBe(1)
  })
})

/**
 * 下单成功提示里的支付窗口（批次 6.0.7）
 *
 * 后端 6.0.6 起窗口按**订单快照的 trade_type** 分档（面交 120 分钟 / 邮寄 15 分钟）。
 * 下单页原来写死「请在 15 分钟内完成支付」⇒ 面交单会被告知错误的时限。
 * 提示文案取的是**商品维度**的 tradeType（订单快照也取商品维度，tradeType=3 的订单快照仍是 3）。
 */
describe('OrderCreateView 下单成功提示按 trade_type 分档', () => {
  async function submitOnce() {
    const wrapper = await mountPage()
    // 邮寄单（tradeType=2）必须填收货地址，否则 el-form 校验失败、确认弹窗根本不会出现
    // （tradeType=3 默认选「面交」，不需要地址）
    const addressInput = wrapper.find('textarea')
    if (addressInput.exists()) {
      await addressInput.setValue('1 号宿舍楼 101 室')
      await flushPromises()
    }
    await wrapper.find('.order-create__submit').trigger('click')
    await flushPromises()
    document.querySelector('.el-message-box__btns .el-button--primary').click()
    await flushPromises()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 60)) // 等 ElMessage 渲染
    return document.querySelector('.el-message')?.textContent || ''
  }

  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    getOrderTokenMock.mockResolvedValue({ token: 'fresh-token', expireSeconds: 300 })
    createOrderMock.mockResolvedValue({ orderId: '99', orderNo: '123', amount: 45, status: 0 })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  function stubProductWithTradeType(tradeType) {
    productDetail.mockResolvedValue({
      id: '30',
      title: '考研数学复习全书 九成新',
      price: 45,
      stock: 3,
      conditionLevel: 2,
      tradeType,
      tradeLocation: '图书馆一楼大厅',
      coverImage: '',
      imageUrls: [],
      status: 1,
      sellerId: '26',
      createTime: '2026-09-16 16:03:43'
    })
  }

  it('商品 tradeType=1（仅面交）→ 提示「请在 120 分钟内完成支付」', async () => {
    stubProductWithTradeType(1)

    const msgText = await submitOnce()

    expect(msgText).toContain('下单成功')
    expect(msgText).toContain('请在 120 分钟内完成支付')
    expect(msgText).not.toContain('请在 15 分钟内完成支付')
  })

  it('商品 tradeType=2（仅邮寄）→ 提示「请在 15 分钟内完成支付」', async () => {
    stubProductWithTradeType(2)

    const msgText = await submitOnce()

    expect(msgText).toContain('请在 15 分钟内完成支付')
  })

  it('商品 tradeType=3（面交/邮寄皆可）→ 与邮寄同档，提示 15 分钟（订单快照也是 3）', async () => {
    stubProductWithTradeType(3)

    const msgText = await submitOnce()

    expect(msgText).toContain('请在 15 分钟内完成支付')
  })
})
