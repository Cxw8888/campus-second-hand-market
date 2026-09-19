/**
 * 订单详情页：加载失败不再无限卡骨架屏
 *
 * 真实 Bug：`loading` 初始化为 true 后**从来没有被置回 false**（全项目只有这个视图漏了复位），
 * 于是接口一失败就永远停在骨架屏，而模板里的错误分支根本不可达。
 * 修法是把 loading 换成显式状态机（loading / success / notFound / error），
 * 本文件逐条守住这四种状态都必须可达、且不会停在 loading。
 *
 * ⚠️ 计时器那条用例用的是假定时器 + nextTick（而不是 flushPromises）：
 *    @vue/test-utils 的 flushPromises 依赖真实 setTimeout，在假定时器下会挂住。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

const getOrderDetailMock = vi.fn()
const getProductDetailMock = vi.fn()
const finishFaceBySellerMock = vi.fn()

vi.mock('@/api/order', () => ({
  getOrderDetail: (...a) => getOrderDetailMock(...a),
  payOrder: vi.fn(),
  cancelOrder: vi.fn(),
  shipOrder: vi.fn(),
  receiveOrder: vi.fn(),
  finishFaceOrder: vi.fn(),
  finishFaceBySellerOrder: (...a) => finishFaceBySellerMock(...a),
  applyRefund: vi.fn(),
  agreeRefund: vi.fn(),
  rejectRefund: vi.fn()
}))

vi.mock('@/api/product', () => ({
  getProductDetail: (...a) => getProductDetailMock(...a),
  getProductList: vi.fn(),
  getMyProducts: vi.fn(),
  createProduct: vi.fn(),
  updateProduct: vi.fn(),
  offShelfProduct: vi.fn(),
  deleteProduct: vi.fn()
}))

import OrderDetailView from '@/views/OrderDetailView.vue'
import { useUserStore } from '@/stores/user'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const ORDER = {
  id: '68',
  orderNo: '358478719859429376',
  userId: '17',
  sellerId: '16',
  productId: '30',
  productTitle: '考研数学复习全书 九成新',
  productCover: '',
  productDeleted: false,
  productPrice: 45,
  amount: 45,
  quantity: 1,
  status: 0,
  address: '图书馆一楼大厅',
  tradeType: 1,
  payTime: null,
  shipTime: null,
  finishTime: null,
  // 刻意用「很久以前」的下单时间：待支付订单一旦超过 15 分钟，PayCountdown 一挂载就 emit('expire')。
  // 这正是「无限骨架屏」第二个成因的触发条件（详见用例⑥），写成固定过去时间才能长期稳定复现。
  createTime: '2020-01-01 00:00:00'
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/order/detail/:orderId', name: 'order-detail', component: OrderDetailView },
      { path: '/order/list', name: 'order-list', component: { template: '<div/>' } },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
}

async function mountPage() {
  const router = makeRouter()
  await router.push('/order/detail/68')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)
  useUserStore().userInfo = { userId: '17', nickname: '买家同学' }

  const wrapper = mount(OrderDetailView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

const hasSkeleton = (wrapper) => wrapper.find('.order-detail__skeleton').exists()

describe('OrderDetailView 四种加载状态', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    getProductDetailMock.mockResolvedValue({
      id: '30',
      title: '考研数学复习全书 九成新',
      sellerNickname: '数院小周',
      tradeLocation: '图书馆一楼大厅',
      imageUrls: []
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('① 成功：正常渲染订单详情，不显示骨架屏', async () => {
    getOrderDetailMock.mockResolvedValue(ORDER)
    const { wrapper } = await mountPage()

    expect(hasSkeleton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain('358478719859429376') // 订单号按字符串原样渲染
    expect(wrapper.text()).toContain('考研数学复习全书 九成新')
    expect(wrapper.text()).toContain('待支付')
  })

  it('② 订单不存在/无权（code=203）：显示「订单不存在或无权访问」+ 返回订单列表，不卡骨架屏', async () => {
    // 后端 requireOrder / 归属校验失败抛的都是 noPermission → 203
    getOrderDetailMock.mockRejectedValue({ code: 203, message: '无权操作该订单' })
    const { wrapper } = await mountPage()

    expect(hasSkeleton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain('订单不存在或无权访问')
    expect(wrapper.text()).toContain('返回订单列表')
  })

  it('③ 网络异常：显示「加载失败，请重试」+ 重试按钮，不卡骨架屏', async () => {
    // request.js 对"没连上后端"抛的是 NetworkError（没有 code 字段）
    const networkError = new Error('无法连接后端服务，请确认 http://127.0.0.1:8080 已启动')
    networkError.name = 'NetworkError'
    getOrderDetailMock.mockRejectedValue(networkError)

    const { wrapper } = await mountPage()

    expect(hasSkeleton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain('加载失败，请重试')
    expect(wrapper.text()).toContain('无法连接后端服务')
    expect(wrapper.text()).toContain('重新加载')
  })

  it('④ error 态点「重新加载」能恢复到成功态', async () => {
    // 第一次加载失败 → error 态
    getOrderDetailMock.mockRejectedValueOnce(new Error('boom'))
    const { wrapper } = await mountPage()
    expect(hasSkeleton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain('加载失败，请重试')

    // 第二次改为成功，点「重新加载」应恢复
    getOrderDetailMock.mockResolvedValue(ORDER)
    const reloadBtn = wrapper.findAll('button').find((b) => b.text().includes('重新加载'))
    expect(reloadBtn).toBeTruthy()

    await reloadBtn.trigger('click')
    await flushPromises()

    expect(hasSkeleton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain('358478719859429376')
    expect(wrapper.text()).toContain('待支付')
  })

  it('⑤ 接口一直挂起：12 秒兜底到 error 态（保证不会无限骨架屏）', async () => {
    // 永不 resolve，模拟"连接挂住但不超时"的病态情况
    getOrderDetailMock.mockImplementation(() => new Promise(() => {}))

    const router = makeRouter()
    await router.push('/order/detail/68')
    await router.isReady()
    const pinia = createPinia()
    setActivePinia(pinia)

    vi.useFakeTimers() // 必须在 mount 之前：兜底定时器是在 onMounted 里创建的
    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
    })
    await nextTick()

    // 一开始确实是骨架屏
    expect(hasSkeleton(wrapper)).toBe(true)

    vi.advanceTimersByTime(12000)
    await nextTick()

    expect(hasSkeleton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain('加载失败，请重试')
    expect(wrapper.text()).toContain('加载超时')
  })

  it('⑥ 待支付且已超时：页面正常渲染，且不会陷入「刷新 ↔ 重建」无限循环', async () => {
    // 这是「无限骨架屏」的第二个成因（真实 Bug，不是测试瑕疵）：
    // 模板上原来是 @expire="reloadAll"，而 emit 不带参数 → reloadAll(undefined)
    // → 命中形参默认值 showSkeleton = true → 切回 loading → 骨架屏分支把 success 分支
    //   （含 PayCountdown 自己）卸载 → 重建后依旧已超时 → 再 emit → 无限循环。
    // 现在父组件显式传 false，子组件也保证同一笔订单只通知一次，两边都不再互相触发。
    getOrderDetailMock.mockResolvedValue(ORDER)
    const { wrapper } = await mountPage()

    // 1) 必须落在成功态：不能停在骨架屏
    expect(hasSkeleton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain('358478719859429376')
    // 2) 倒计时确实挂载了（已超时文案），说明死循环的触发条件真实存在
    expect(wrapper.text()).toContain('已超过支付时限')
    // 3) 关键断言：只允许「首次加载 + 超时后静默刷新一次」，多一次就说明又转起来了
    expect(getOrderDetailMock).toHaveBeenCalledTimes(2)
    // 4) 兜底再推进一步时间，确认不会继续自我触发
    await new Promise((resolve) => setTimeout(resolve, 30))
    await flushPromises()
    expect(getOrderDetailMock).toHaveBeenCalledTimes(2)
    expect(hasSkeleton(wrapper)).toBe(false)
  })
})

/**
 * 6.0.5.1 · M2：卖家确认面交完成（已支付面交单 1→3）
 *
 * 守的是「已支付 + 面交」这一类订单的**按钮可见性规则**（项目约定：状态规则必须有单测）：
 *   卖家看到「确认面交完成」；买家看到的是「申请退款」；邮寄单卖家看到的是「发货」。
 * 最后一例走真实 ElMessageBox（与 AdminProductAuditView 的用例同一套路），
 * 验证「弹窗确认 → 调接口 → 刷新」这条链路真的接通。
 */
describe('OrderDetailView 卖家确认面交完成（6.0.5.1 · M2）', () => {
  /** 已支付、面交、待收货（修复前"无路可走"的那一类订单） */
  const PAID_FACE_ORDER = { ...ORDER, status: 1, tradeType: 1, payTime: '2026-09-19 10:00:00' }
  const SELLER_ID = String(ORDER.sellerId) // '16'
  const BUYER_ID = String(ORDER.userId) // '17'

  /**
   * ⚠️ 必须按【按钮】断言，不能对整页 text() 做包含判断：
   *   订单进度时间线里本来就有「卖家 / 确认面交完成」这样的节点文案，
   *   用 wrapper.text() 判包含会把时间线误判成按钮（本批实测踩到过）。
   */
  const buttonTexts = (wrapper) => wrapper.findAll('button').map((b) => b.text())
  const hasButton = (wrapper, label) => buttonTexts(wrapper).some((t) => t.includes(label))

  async function mountAs(userId, order) {
    getOrderDetailMock.mockResolvedValue(order)
    const router = makeRouter()
    await router.push('/order/detail/68')
    await router.isReady()

    const pinia = createPinia()
    setActivePinia(pinia)
    useUserStore().userInfo = { userId, nickname: '同学' }

    const wrapper = mount(OrderDetailView, {
      global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
    })
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    getProductDetailMock.mockResolvedValue({
      id: '30',
      title: '考研数学复习全书 九成新',
      sellerNickname: '数院小周',
      tradeLocation: '图书馆一楼大厅',
      imageUrls: []
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('⑦ 卖家 + 已支付 + 面交 → 显示「确认面交完成」按钮', async () => {
    const wrapper = await mountAs(SELLER_ID, PAID_FACE_ORDER)

    expect(hasSkeleton(wrapper)).toBe(false)
    expect(hasButton(wrapper, '确认面交完成')).toBe(true)
    // 待支付的「确认已完成」此时不该出现（那是 0→3 的路径），买家侧的按钮也不该出现
    expect(hasButton(wrapper, '去支付')).toBe(false)
    expect(hasButton(wrapper, '申请退款')).toBe(false)
  })

  it('⑧ 买家 + 已支付 + 面交 → 没有该按钮（买家走「申请退款」）', async () => {
    const wrapper = await mountAs(BUYER_ID, PAID_FACE_ORDER)

    expect(hasButton(wrapper, '确认面交完成')).toBe(false)
    expect(hasButton(wrapper, '申请退款')).toBe(true)
  })

  it('⑨ 邮寄单 + 已支付 + 卖家 → 显示「发货」，不显示「确认面交完成」', async () => {
    const wrapper = await mountAs(SELLER_ID, { ...PAID_FACE_ORDER, tradeType: 2 })

    expect(hasButton(wrapper, '发货')).toBe(true)
    expect(hasButton(wrapper, '确认面交完成')).toBe(false)
  })

  it('⑩ 点「确认面交完成」→ 真实确认弹窗 → 确认后调接口并刷新详情', async () => {
    const wrapper = await mountAs(SELLER_ID, PAID_FACE_ORDER)
    const before = getOrderDetailMock.mock.calls.length

    const btn = wrapper.findAll('button').find((b) => b.text().includes('确认面交完成'))
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()

    // ElMessageBox 挂到 body 上，用 document 查（与 AdminProductAuditView ⑦ 同一套路）
    const confirmBtn = document.querySelector('.el-message-box__btns .el-button--primary')
    expect(confirmBtn).toBeTruthy()
    confirmBtn.click()
    await flushPromises()
    await flushPromises()

    expect(finishFaceBySellerMock).toHaveBeenCalledTimes(1)
    expect(finishFaceBySellerMock).toHaveBeenCalledWith('68')
    // 操作成功后必须刷新详情（runAction 里的 reloadAll(false)）
    expect(getOrderDetailMock.mock.calls.length).toBe(before + 1)
  })

  it('⑪ 取消确认弹窗 → 不发请求', async () => {
    const wrapper = await mountAs(SELLER_ID, PAID_FACE_ORDER)

    const btn = wrapper.findAll('button').find((b) => b.text().includes('确认面交完成'))
    await btn.trigger('click')
    await flushPromises()

    const cancelBtn = document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)')
    expect(cancelBtn).toBeTruthy()
    cancelBtn.click()
    await flushPromises()

    expect(finishFaceBySellerMock).not.toHaveBeenCalled()
  })
})
