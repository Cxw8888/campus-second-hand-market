/**
 * 管理端 · 订单管理页（第五批 5.2）
 *
 * 重点守：
 *   ① 操作按 **status 前置显示**：只有 5 才有解冻、只有 6/7 才有强制退款，
 *      其余状态留白 —— 否则会打到后端那条 `code=200 + 请勿重复操作`（组件感知不到）
 *   ② `unfreeze.target` 必须**大写**走 body：小写后端直接 100
 *   ③ `forceRefund` 的 reason 走**参数位**（api 层把它拼进 query），不是 body
 *   ④ 同步锁按 `${id}:${action}` 隔离：同一行两个操作不能互相串 loading、也不能并发提交
 *   ⑤ 209 → 静默刷新 + info 提示
 *   ⑥ 5 态（含 403 独立态）
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

const getOrderListMock = vi.fn()
const unfreezeMock = vi.fn()
const forceRefundMock = vi.fn()

vi.mock('@/api/admin', () => ({
  getAdminOrderList: (...args) => getOrderListMock(...args),
  unfreezeOrder: (...args) => unfreezeMock(...args),
  forceRefundOrder: (...args) => forceRefundMock(...args),
  // 本页用不到，保持模块形状完整
  getAdminUserList: vi.fn(),
  banUser: vi.fn(),
  unbanUser: vi.fn(),
  getAdminProductList: vi.fn(),
  auditProduct: vi.fn(),
  forceOfflineProduct: vi.fn(),
  getAuditLogList: vi.fn(),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  deleteCategory: vi.fn(),
  migrateCategory: vi.fn()
}))

import AdminOrderView, { resolvePlaceHeader } from '@/views/admin/AdminOrderView.vue'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const baseOrder = {
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
  createTime: '2026-09-16 16:03:43'
}

const FROZEN_ORDER = { ...baseOrder, id: '88', orderNo: '358478719859429888', status: 5 }
const REFUND_ORDER = { ...baseOrder, id: '99', orderNo: '358478719859429899', status: 6 }
/** 邮寄订单（tradeType=2）：用于"地点/地址"列表头切换用例 */
const MAIL_ORDER = {
  ...baseOrder,
  id: '77',
  orderNo: '358478719859429777',
  tradeType: 2,
  address: '1号宿舍楼101室',
  status: 1
}

async function mountPage(records = [baseOrder]) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/order', name: 'admin-order', component: AdminOrderView },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/admin/order')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  getOrderListMock.mockResolvedValue({
    total: records.length,
    pages: 1,
    current: 1,
    size: 10,
    records
  })

  const wrapper = mount(AdminOrderView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

/** el-table 会把每列模板再渲染一份到 .hidden-columns（row 是空对象），必须限定在真实数据行内找按钮 */
const findRowButton = (wrapper, text) =>
  wrapper.findAll('.el-table__body button').find((node) => node.text().includes(text))

const confirmPrimary = () => document.querySelector('.el-message-box__btns .el-button--primary')

describe('AdminOrderView 订单管理', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    unfreezeMock.mockResolvedValue(null)
    forceRefundMock.mockResolvedValue(null)
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('① 默认「全部」：status 下发 null（不过滤），渲染订单号/金额/状态', async () => {
    const { wrapper } = await mountPage()

    expect(getOrderListMock).toHaveBeenCalledTimes(1)
    const [params] = getOrderListMock.mock.calls[0]
    expect(params).toMatchObject({ status: null, page: 1 })

    expect(wrapper.text()).toContain('358478719859429376') // 订单号按字符串原样渲染
    expect(wrapper.text()).toContain('考研数学复习全书 九成新')
    expect(wrapper.text()).toContain('¥45.00')
    expect(wrapper.text()).toContain('待支付')
    expect(wrapper.text()).toContain('买家')
    expect(wrapper.text()).toContain('卖家')
  })

  it('② 切换「退款申请中」页签 → status=6 重新请求', async () => {
    const { wrapper } = await mountPage()
    const tab = wrapper.findAll('.admin-order__tab').find((t) => t.text().includes('退款申请中'))
    expect(tab).toBeTruthy()
    await tab.trigger('click')
    await flushPromises()

    expect(getOrderListMock.mock.calls[1][0]).toMatchObject({ status: 6, page: 1 })
  })

  it('③ 订单号精确查询：传 orderNo 且页码回到第 1 页', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.admin-order__search input').setValue('358478719859429376')
    await wrapper.find('.admin-order__search button').trigger('click')
    await flushPromises()

    expect(getOrderListMock.mock.calls[1][0]).toMatchObject({
      orderNo: '358478719859429376',
      page: 1
    })
  })

  it('④ 查不到订单时，空状态要说明"订单号是精确匹配"（避免管理员以为搜索坏了）', async () => {
    const { wrapper } = await mountPage([])
    await wrapper.find('.admin-order__search input').setValue('123')
    await wrapper.find('.admin-order__search button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('没有这个订单号')
    expect(wrapper.text()).toContain('精确匹配')
  })

  it('⑤ 加载失败 → 错误态；403 → 独立的无权限态', async () => {
    getOrderListMock.mockRejectedValueOnce(new Error('无法连接后端服务'))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('加载失败，请重试')

    getOrderListMock.mockRejectedValueOnce({ code: 403, message: '无权限访问' })
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重新加载'))
    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('没有管理权限')
    expect(wrapper.find('.admin-order__table').exists()).toBe(false)
  })

  it('⑥ 非 5/6/7 的订单不显示任何操作按钮（不放假按钮）', async () => {
    const { wrapper } = await mountPage([baseOrder]) // status = 0

    expect(findRowButton(wrapper, '解冻为已取消')).toBeFalsy()
    expect(findRowButton(wrapper, '线下完成')).toBeFalsy()
    expect(findRowButton(wrapper, '强制退款')).toBeFalsy()
    expect(wrapper.find('.admin-order__no-action').exists()).toBe(true)
  })

  it('⑦ 已冻结订单：解冻为已取消 → target=大写 CANCEL 走 body', async () => {
    const { wrapper } = await mountPage([FROZEN_ORDER])

    const btn = findRowButton(wrapper, '解冻为已取消')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    confirmPrimary().click()
    await flushPromises()
    await flushPromises()

    expect(unfreezeMock).toHaveBeenCalledWith('88', 'CANCEL')
    expect(getOrderListMock).toHaveBeenCalledTimes(2)
  })

  it('⑧ 已冻结订单：线下完成 → target=大写 COMPLETE', async () => {
    const { wrapper } = await mountPage([FROZEN_ORDER])

    const btn = findRowButton(wrapper, '线下完成')
    await btn.trigger('click')
    await flushPromises()
    confirmPrimary().click()
    await flushPromises()
    await flushPromises()

    expect(unfreezeMock).toHaveBeenCalledWith('88', 'COMPLETE')
  })

  it('⑨ 退款申请中：强制退款要求填原因，reason 走参数位（api 层拼进 query）', async () => {
    const { wrapper } = await mountPage([REFUND_ORDER])

    const btn = findRowButton(wrapper, '强制退款')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()

    const input = document.querySelector('.el-message-box input.el-input__inner')
    expect(input).toBeTruthy()

    // 不填原因 → 校验拦住，不发请求
    confirmPrimary().click()
    await flushPromises()
    expect(forceRefundMock).not.toHaveBeenCalled()

    input.value = '卖家超时未处理'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    confirmPrimary().click()
    await flushPromises()
    await flushPromises()

    expect(forceRefundMock).toHaveBeenCalledWith('99', '卖家超时未处理')
  })

  it('⑩ 同步锁按 ${id}:${action} 隔离：同一行点了解冻后，另一个操作按钮同时禁用', async () => {
    const { wrapper } = await mountPage([FROZEN_ORDER])

    const cancelBtn = findRowButton(wrapper, '解冻为已取消')
    const completeBtn = findRowButton(wrapper, '线下完成')

    // 连点 5 次「解冻为已取消」→ 只弹 1 个确认框、只发 1 次请求
    for (let i = 0; i < 5; i++) {
      await cancelBtn.trigger('click')
    }
    await flushPromises()
    expect(document.querySelectorAll('.el-message-box').length).toBe(1)

    // 同一行的另一个操作已被锁住，避免「两个终态」并发提交
    expect(completeBtn.classes()).toContain('is-disabled')

    confirmPrimary().click()
    await flushPromises()
    await flushPromises()
    expect(unfreezeMock).toHaveBeenCalledTimes(1)
  })

  it('⑪ 操作遇到 209 → 静默刷新 + info 提示（不弹"操作失败"）', async () => {
    unfreezeMock.mockRejectedValueOnce({ code: 209, message: '当前状态不允许此操作' })
    const { wrapper } = await mountPage([FROZEN_ORDER])

    const btn = findRowButton(wrapper, '解冻为已取消')
    await btn.trigger('click')
    await flushPromises()
    confirmPrimary().click()
    await flushPromises()
    await flushPromises()

    expect(getOrderListMock).toHaveBeenCalledTimes(2)
    const toast = document.querySelector('.el-message')?.textContent || ''
    expect(toast).toContain('列表已更新')
  })

  // ---------------- 5.2.1 补丁项 ----------------

  it('⑫ 「地点 / 地址」列：面交订单渲染约定地点，表头切成「面交地点」', async () => {
    const { wrapper } = await mountPage([baseOrder]) // tradeType=1，address 存的是面交约定地点

    expect(wrapper.text()).toContain('图书馆一楼大厅')
    expect(wrapper.text()).toContain('面交地点')
  })

  it('⑬ 当前页混有邮寄订单时，表头用中性叫法（不写只对一半数据成立的名字）', async () => {
    const { wrapper } = await mountPage([baseOrder, MAIL_ORDER])

    expect(wrapper.text()).toContain('图书馆一楼大厅') // 面交约定地点
    expect(wrapper.text()).toContain('1号宿舍楼101室') // 邮寄收货地址
    expect(wrapper.text()).toContain('地点 / 地址')
    expect(wrapper.text()).not.toContain('面交地点')
  })

  it('⑭ 「已发货」页签点明"仅邮寄"（面交订单状态流转 0→1→3，不会经过已发货）', async () => {
    const { wrapper } = await mountPage()

    const tab = wrapper.findAll('.admin-order__tab').find((t) => t.text().includes('已发货'))
    expect(tab).toBeTruthy()
    // 状态名本身来自 ORDER_STATUS_MAP（2 = 已发货待收货），管理端只在后面补一句"仅邮寄"，
    // 不重写状态名 —— 否则页签与行内 OrderStatusTag 会自相矛盾。
    expect(tab.text()).toBe('已发货待收货（仅邮寄）')
    // 全局字典不能被动过：行内状态标签仍是字典里的原文
    expect(wrapper.text()).toContain('待支付')
  })

  // ---------------- 5.2.2 补丁项 ----------------

  it('⑮ 空列表时表头必须是中性「地点 / 地址」，不能是空字符串/undefined', async () => {
    // 纯函数直接覆盖边界：直接看 computed 是测不到的 ——
    // 空列表会先落到「空状态」分支，表格根本不渲染，DOM 里没有表头可断言。
    expect(resolvePlaceHeader([])).toBe('地点 / 地址')
    expect(resolvePlaceHeader(undefined)).toBe('地点 / 地址')
    expect(resolvePlaceHeader([{ tradeType: 3 }])).toBe('地点 / 地址') // 两者皆可 → 中性
    expect(resolvePlaceHeader([{ tradeType: 1 }])).toBe('面交地点')
    expect(resolvePlaceHeader([{ tradeType: 2 }])).toBe('收货地址')
    expect(resolvePlaceHeader([{ tradeType: 1 }, { tradeType: 2 }])).toBe('地点 / 地址')

    // DOM 侧同时确认：空结果时不会出现一个"写着面交地点却没有任何数据"的表头
    const { wrapper } = await mountPage([])
    expect(wrapper.find('.admin-order__table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('面交地点')
  })
})
