/**
 * 管理端 · 商品审核页（第五批 5.1）
 *
 * 重点守四类东西：
 *   ① 请求参数必须显式带 status —— 后端 AdminProductQuery.status 默认值是 3，
 *      **省略参数 ≠ 全部**，写错了页面就会"永远只显示待审核"，还看不出错。
 *   ② 四个状态必须都可达，且 loading 一定要复位（批次 3 的无限骨架屏事故就是这么来的）。
 *   ③ 操作参数必须按后端契约走：审核走 body、强制下架的 reason 走 **query**。
 *   ④ 防连点必须**同步上锁**（批次 4 的教训）：连点 5 次只能发 1 次请求。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

// ---------------- 接口打桩 ----------------
const getListMock = vi.fn()
const auditMock = vi.fn()
const forceOfflineMock = vi.fn()

vi.mock('@/api/admin', () => ({
  getAdminProductList: (...args) => getListMock(...args),
  auditProduct: (...args) => auditMock(...args),
  forceOfflineProduct: (...args) => forceOfflineMock(...args),
  // 本页用不到，保持模块形状完整（5.2/5.3 会补齐）
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

import AdminProductAuditView from '@/views/admin/AdminProductAuditView.vue'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const PENDING_ROW = {
  id: '30',
  title: '考研数学复习全书 九成新',
  price: 45,
  stock: 1,
  conditionLevel: 2,
  tradeType: 1,
  tradeLocation: '图书馆一楼大厅',
  coverImage: '',
  categoryId: '1',
  categoryName: '教材书籍',
  status: 3,
  createTime: '2026-09-16 16:03:43',
  sellerId: '26',
  sellerNickname: '数院小周'
}

const ON_SALE_ROW = { ...PENDING_ROW, id: '31', title: '罗技鼠标 M330', status: 1, sellerNickname: '计院小李' }

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/product/audit', name: 'admin-product-audit', component: AdminProductAuditView },
      { path: '/403', name: 'forbidden', component: { template: '<div/>' } },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/admin/product/audit')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  const wrapper = mount(AdminProductAuditView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

const findByText = (wrapper, selector, text) =>
  wrapper.findAll(selector).find((node) => node.text().includes(text))

/**
 * 在**真实数据行**里找操作按钮。
 *
 * ⚠️ 必须限定在 .el-table__body 内，不能直接 wrapper.findAll('button')：
 *    el-table 会把每一列的模板**再渲染一份**到 .hidden-columns（列布局用的影子副本），
 *    而且那份的 row 是**空对象 {}** —— 于是 `Number({}.status) === 3` 恒为 false，
 *    影子副本里永远渲染的是「强制下架」分支。
 *    本批实测：不限定范围时 findByText('强制下架') 会先命中影子副本，
 *    点下去拿到的是空 row，接口参数就变成了 undefined —— 而且断言报错信息完全看不出原因。
 */
const findRowButton = (wrapper, text) =>
  wrapper.findAll('.el-table__body button').find((node) => node.text().includes(text))

describe('AdminProductAuditView 商品审核', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    getListMock.mockResolvedValue({ total: 1, pages: 1, current: 1, size: 10, records: [PENDING_ROW] })
    auditMock.mockResolvedValue(null)
    forceOfflineMock.mockResolvedValue(null)
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('① 默认查「待审核」：显式带 status=3，并渲染出商品', async () => {
    const { wrapper } = await mountPage()

    expect(getListMock).toHaveBeenCalledTimes(1)
    const [params] = getListMock.mock.calls[0]
    // status 必须显式传：省略就等于后端默认的 3，看起来一样但语义完全不同
    // （size 的默认值由 api 层补，这里只锁页面自己传了什么）
    expect(params).toMatchObject({ status: 3, page: 1 })

    expect(wrapper.text()).toContain('考研数学复习全书 九成新')
    expect(wrapper.text()).toContain('数院小周')
    expect(wrapper.text()).toContain('待审核')
    expect(wrapper.text()).toContain('共 1 件')
  })

  it('② 切换状态页签 → 用新的 status 重新请求，并回到第 1 页', async () => {
    const { wrapper } = await mountPage()

    const onSaleTab = findByText(wrapper, '.admin-product__tab', '在售')
    expect(onSaleTab).toBeTruthy()
    await onSaleTab.trigger('click')
    await flushPromises()

    expect(getListMock).toHaveBeenCalledTimes(2)
    expect(getListMock.mock.calls[1][0]).toMatchObject({ status: 1, page: 1 })
  })

  it('③ 空列表 → 空状态，不显示表格也不卡骨架屏', async () => {
    getListMock.mockResolvedValue({ total: 0, pages: 0, current: 1, size: 10, records: [] })
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('没有待审核的商品')
    expect(wrapper.find('.admin-product__skeleton').exists()).toBe(false)
    expect(wrapper.find('.admin-product__table').exists()).toBe(false)
  })

  it('④ 加载失败 → 「加载失败，请重试」+ 重新加载按钮，且骨架屏已消失', async () => {
    getListMock.mockRejectedValue(new Error('无法连接后端服务'))
    const { wrapper } = await mountPage()

    expect(wrapper.find('.admin-product__skeleton').exists()).toBe(false)
    expect(wrapper.text()).toContain('加载失败，请重试')
    expect(wrapper.text()).toContain('无法连接后端服务')

    // 点重新加载 → 第二次改成成功，页面应恢复列表
    getListMock.mockResolvedValue({ total: 1, pages: 1, current: 1, size: 10, records: [PENDING_ROW] })
    const retry = findByText(wrapper, 'button', '重新加载')
    expect(retry).toBeTruthy()
    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('考研数学复习全书 九成新')
    expect(getListMock).toHaveBeenCalledTimes(2)
  })

  it('⑤ 后端返回 403（非管理员）→ 专门的「没有管理权限」态', async () => {
    getListMock.mockRejectedValue({ code: 403, message: '无权限访问' })
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('没有管理权限')
    expect(wrapper.text()).toContain('返回首页')
    expect(wrapper.find('.admin-product__table').exists()).toBe(false)
  })

  it('⑥ 点「通过」→ 按契约传 {pass:true, reason:""} 且刷新列表', async () => {
    const { wrapper } = await mountPage()

    const passBtn = findRowButton(wrapper, '通过')
    expect(passBtn).toBeTruthy()
    await passBtn.trigger('click')
    await flushPromises()

    expect(auditMock).toHaveBeenCalledTimes(1)
    expect(auditMock).toHaveBeenCalledWith('30', { pass: true, reason: '' })
    // 操作后必须刷新（列表调用次数 +1）
    expect(getListMock).toHaveBeenCalledTimes(2)
  })

  it('⑦ 点「驳回」→ 弹窗要求填原因；空原因被拦下，填了才提交', async () => {
    const { wrapper } = await mountPage()

    const rejectBtn = findRowButton(wrapper, '驳回')
    await rejectBtn.trigger('click')
    await flushPromises()

    // 走的是真实的 ElMessageBox.prompt（跟 OrderCreateView 的用例同一套路）
    const input = document.querySelector('.el-message-box input.el-input__inner')
    expect(input).toBeTruthy()

    // ① 不填原因直接确认 → 校验拦住，不发请求，弹窗仍在
    document.querySelector('.el-message-box__btns .el-button--primary').click()
    await flushPromises()
    expect(auditMock).not.toHaveBeenCalled()
    expect(document.querySelector('.el-message-box')).toBeTruthy()

    // ② 填了原因再确认 → 正常提交
    input.value = '图片不清晰'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    document.querySelector('.el-message-box__btns .el-button--primary').click()
    await flushPromises()
    await flushPromises()

    expect(auditMock).toHaveBeenCalledWith('30', { pass: false, reason: '图片不清晰' })
  })

  it('⑧ 连点 5 次「通过」→ 同步锁只放行 1 次请求（批次 4 的防连点回归）', async () => {
    const { wrapper } = await mountPage()

    const passBtn = findRowButton(wrapper, '通过')
    for (let i = 0; i < 5; i++) {
      await passBtn.trigger('click')
    }
    await flushPromises()

    expect(auditMock).toHaveBeenCalledTimes(1)
  })

  it('⑨ 审核时遇到 209（状态已被别处改掉）→ 自动刷新列表回到真实状态', async () => {
    auditMock.mockRejectedValueOnce({ code: 209, message: '当前状态不允许此操作' })
    const { wrapper } = await mountPage()

    const passBtn = findRowButton(wrapper, '通过')
    await passBtn.trigger('click')
    await flushPromises()
    await flushPromises()

    // 首次加载 + 遇到 209 后的自动刷新 = 2 次
    expect(getListMock).toHaveBeenCalledTimes(2)
  })

  it('⑩ 非待审核商品显示「强制下架」；强制下架的 reason 走 query，不是 body', async () => {
    getListMock.mockResolvedValue({ total: 1, pages: 1, current: 1, size: 10, records: [ON_SALE_ROW] })
    const { wrapper } = await mountPage()

    // 已上架商品没有「通过/驳回」，只有强制下架
    expect(findRowButton(wrapper, '通过')).toBeFalsy()
    const offlineBtn = findRowButton(wrapper, '强制下架')
    expect(offlineBtn).toBeTruthy()

    await offlineBtn.trigger('click')
    await flushPromises()

    const input = document.querySelector('.el-message-box input.el-input__inner')
    expect(input).toBeTruthy()
    input.value = '涉嫌违规内容'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    document.querySelector('.el-message-box__btns .el-button--primary').click()
    await flushPromises()
    await flushPromises()

    // 第二个参数是 reason（api 层会把它拼到 query 上），第三个参数不存在 —— 契约形状必须锁住
    expect(forceOfflineMock).toHaveBeenCalledWith('31', '涉嫌违规内容')
  })
})
