/**
 * 管理端 · 用户管理页（第五批 5.2）
 *
 * 重点守：
 *   ① 「全部」页签必须以下发 status=null 表达（后端 AdminUserQuery.status 无默认值 → 省略即不过滤）
 *   ② 封禁/解封的**幂等**靠前置禁用：已封禁的行封禁按钮必须 disabled，否则会打到
 *      后端那条 `code=200 + 请勿重复操作` 的路径（组件根本感知不到）
 *   ③ **不能封禁自己**：后端没有任何校验（已读源码核实），只能前端拦
 *   ④ 连点只发一次请求（同步锁，批次 4 的教训）
 *   ⑤ 列表 4 态 + 403 单独一态
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import { ElTooltip } from 'element-plus'

const getUserListMock = vi.fn()
const banMock = vi.fn()
const unbanMock = vi.fn()
const getProductListMock = vi.fn()

vi.mock('@/api/admin', () => ({
  getAdminUserList: (...args) => getUserListMock(...args),
  banUser: (...args) => banMock(...args),
  unbanUser: (...args) => unbanMock(...args),
  getAdminProductList: (...args) => getProductListMock(...args),
  // 本页用不到，保持模块形状完整
  auditProduct: vi.fn(),
  forceOfflineProduct: vi.fn(),
  getAdminOrderList: vi.fn(),
  unfreezeOrder: vi.fn(),
  forceRefundOrder: vi.fn(),
  getAuditLogList: vi.fn(),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  deleteCategory: vi.fn(),
  migrateCategory: vi.fn()
}))

import AdminUserView from '@/views/admin/AdminUserView.vue'
import { useUserStore } from '@/stores/user'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const NORMAL_ROW = {
  id: '26',
  username: '2021001',
  nickname: '数院小周',
  avatar: '',
  phone: '13800000000',
  email: 'zhou@stu.edu.cn',
  role: 0,
  status: 0,
  createTime: '2026-09-01 10:00:00'
}

const BANNED_ROW = { ...NORMAL_ROW, id: '27', username: '2021002', nickname: '计院小李', status: 1 }

/** 另一个管理员（role=1）：用于角色筛选用例 */
const ADMIN_ROW = {
  ...NORMAL_ROW,
  id: '2',
  username: 'admin41298705',
  nickname: '管理员小张',
  email: 'admin2@stu.edu.cn',
  role: 1
}

/** 当前登录的管理员（id=1），用于「不能封禁自己」用例 */
const SELF_ROW = {
  id: '1',
  username: 'admin41298705',
  nickname: '管理员',
  email: 'admin@stu.edu.cn',
  role: 1,
  status: 0,
  createTime: '2026-08-01 09:00:00'
}

async function mountPage({ userId = '1', records = [NORMAL_ROW] } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/user', name: 'admin-user', component: AdminUserView },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/admin/user')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)
  useUserStore().userInfo = { userId, nickname: '管理员', role: 1 }

  getUserListMock.mockResolvedValue({
    total: records.length,
    pages: 1,
    current: 1,
    size: 10,
    records
  })

  const wrapper = mount(AdminUserView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

/** el-table 会把每列模板再渲染一份到 .hidden-columns（row 是空对象），必须限定在真实数据行内找按钮 */
const findRowButton = (wrapper, text) =>
  wrapper.findAll('.el-table__body button').find((node) => node.text().includes(text))

const confirmBox = () => document.querySelector('.el-message-box')
const confirmPrimary = () => document.querySelector('.el-message-box__btns .el-button--primary')

describe('AdminUserView 用户管理', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    getProductListMock.mockResolvedValue({ total: 3, pages: 1, current: 1, size: 10, records: [] })
    banMock.mockResolvedValue(null)
    unbanMock.mockResolvedValue(null)
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('① 默认「全部」：status 下发 null（等于不过滤），并渲染用户信息', async () => {
    const { wrapper } = await mountPage()

    expect(getUserListMock).toHaveBeenCalledTimes(1)
    const [params] = getUserListMock.mock.calls[0]
    // null 会被 api 层的 pruneEmpty 丢掉 → 请求里没有 status 参数 → 后端不过滤（这是「全部」的正确表达）
    expect(params).toMatchObject({ status: null, page: 1 })

    expect(wrapper.text()).toContain('数院小周')
    expect(wrapper.text()).toContain('2021001')
    expect(wrapper.text()).toContain('zhou@stu.edu.cn')
    expect(wrapper.text()).toContain('学生')
    expect(wrapper.text()).toContain('正常')
  })

  it('② 切换「已封禁」页签 → status=1 重新请求，且页码回到第 1 页', async () => {
    const { wrapper } = await mountPage({ records: [NORMAL_ROW, BANNED_ROW] })

    // 先翻到第 2 页（total 给大一点让分页器出现）
    getUserListMock.mockResolvedValue({ total: 25, pages: 3, current: 2, size: 10, records: [NORMAL_ROW] })
    await wrapper.find('.admin-user__tab:nth-child(3)').trigger('click') // 已封禁
    await flushPromises()

    const firstCall = getUserListMock.mock.calls[0][0]
    const secondCall = getUserListMock.mock.calls[1][0]
    expect(firstCall.page).toBe(1)
    expect(secondCall).toMatchObject({ status: 1, page: 1 })
  })

  it('③ 空列表 → 空状态（「全部」时文案是"还没有任何注册用户"）', async () => {
    const { wrapper } = await mountPage({ records: [] })

    expect(wrapper.text()).toContain('还没有任何注册用户')
    expect(wrapper.find('.admin-user__skeleton').exists()).toBe(false)
    expect(wrapper.find('.admin-user__table').exists()).toBe(false)
  })

  it('④ 加载失败 → 错误态 + 重新加载可恢复；403 → 独立的无权限态', async () => {
    getUserListMock.mockRejectedValueOnce(new Error('无法连接后端服务'))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('加载失败，请重试')
    expect(wrapper.find('.admin-user__skeleton').exists()).toBe(false)

    // 重试：第二次改成 403 → 应落到"没有管理权限"
    getUserListMock.mockRejectedValueOnce({ code: 403, message: '无权限访问' })
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重新加载'))
    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('没有管理权限')
    expect(wrapper.find('.admin-user__table').exists()).toBe(false)
  })

  it('⑤ 封禁：二次确认后调 banUser，并提示名下下架商品数（接口无数据 → 靠一次静默统计查询）', async () => {
    const { wrapper } = await mountPage()

    const banBtn = findRowButton(wrapper, '封禁')
    expect(banBtn).toBeTruthy()
    await banBtn.trigger('click')
    await flushPromises()

    expect(confirmBox()).toBeTruthy() // 真实 ElMessageBox DOM，不 mock
    confirmPrimary().click()
    await flushPromises()
    await flushPromises()

    expect(banMock).toHaveBeenCalledTimes(1)
    expect(banMock).toHaveBeenCalledWith('26')
    // 统计该用户名下 status=0 的商品数（ban 返回 Result<Void>，拿不到数字）
    expect(getProductListMock).toHaveBeenCalledWith(
      { userId: '26', status: 0 },
      { silent: true }
    )
    // 列表刷新了一次（首次加载 + 封禁后）
    expect(getUserListMock).toHaveBeenCalledTimes(2)

    const toast = document.querySelector('.el-message')?.textContent || ''
    expect(toast).toContain('3 件商品已下架')
  })

  it('⑥ 不能封禁自己：当前登录账号那一行的封禁按钮必须禁用（后端无任何校验）', async () => {
    const { wrapper } = await mountPage({ userId: '1', records: [SELF_ROW] })

    const banBtn = findRowButton(wrapper, '封禁')
    expect(banBtn).toBeTruthy()
    expect(banBtn.classes()).toContain('is-disabled')

    // 点了也不该发请求
    await banBtn.trigger('click')
    await flushPromises()
    expect(banMock).not.toHaveBeenCalled()
    expect(confirmBox()).toBeFalsy()
  })

  it('⑦ 已封禁的行：封禁按钮禁用（幂等前置拦截），解封按钮出现且可用', async () => {
    const { wrapper } = await mountPage({ records: [BANNED_ROW] })

    const banBtn = findRowButton(wrapper, '封禁')
    expect(banBtn.classes()).toContain('is-disabled')

    const unbanBtn = findRowButton(wrapper, '解封')
    expect(unbanBtn).toBeTruthy()
    expect(unbanBtn.classes()).not.toContain('is-disabled')

    await unbanBtn.trigger('click')
    await flushPromises()
    confirmPrimary().click()
    await flushPromises()
    await flushPromises()

    expect(unbanMock).toHaveBeenCalledWith('27')
  })

  it('⑧ 连点 5 次「封禁」→ 同步锁只发 1 次请求、只弹 1 个确认框', async () => {
    const { wrapper } = await mountPage()

    const banBtn = findRowButton(wrapper, '封禁')
    for (let i = 0; i < 5; i++) {
      await banBtn.trigger('click')
    }
    await flushPromises()

    expect(document.querySelectorAll('.el-message-box').length).toBe(1)
    expect(banMock).not.toHaveBeenCalled() // 还没确认

    confirmPrimary().click()
    await flushPromises()
    await flushPromises()
    expect(banMock).toHaveBeenCalledTimes(1)
  })

  it('⑨ 操作遇到 209（状态已被别处改掉）→ 静默刷新 + info 提示，不弹"操作失败"', async () => {
    banMock.mockRejectedValueOnce({ code: 209, message: '当前状态不允许此操作' })
    const { wrapper } = await mountPage()

    const banBtn = findRowButton(wrapper, '封禁')
    await banBtn.trigger('click')
    await flushPromises()
    confirmPrimary().click()
    await flushPromises()
    await flushPromises()

    expect(getUserListMock).toHaveBeenCalledTimes(2) // 首次 + 209 后自动刷新
    const toast = document.querySelector('.el-message')?.textContent || ''
    expect(toast).toContain('列表已更新')
  })

  // ---------------- 5.2.1 补丁项 ----------------

  it('⑩ 学号是独立一列（不再塞在「用户」单元格里）', async () => {
    const { wrapper } = await mountPage()

    const cell = wrapper.find('.admin-user__username')
    expect(cell.exists()).toBe(true)
    expect(cell.text()).toBe('2021001')
  })

  it('⑪ 角色筛选：选中「管理员」→ 一次性拉满(size=100) 后前端过滤，只显示管理员', async () => {
    const { wrapper } = await mountPage({ records: [] })
    getUserListMock.mockResolvedValue({
      total: 3,
      pages: 1,
      current: 1,
      size: 100,
      records: [ADMIN_ROW, NORMAL_ROW, BANNED_ROW]
    })

    const roleTab = wrapper.findAll('.admin-user__tab').find((t) => t.text() === '管理员')
    expect(roleTab).toBeTruthy()
    await roleTab.trigger('click')
    await flushPromises()

    // 后端没有 role 参数 → 只能拉满后在本地过滤，参数必须是 page=1 + size=100(MAX_PAGE_SIZE)
    expect(getUserListMock.mock.calls[1][0]).toMatchObject({ page: 1, size: 100 })
    expect(wrapper.text()).toContain('管理员小张')
    expect(wrapper.text()).not.toContain('数院小周')
    expect(wrapper.text()).not.toContain('计院小李')
  })

  it('⑫ 用户总数超过一次性能拉取的上限时，明确提示"结果可能不完整"（不假装完整）', async () => {
    const { wrapper } = await mountPage({ records: [] })
    getUserListMock.mockResolvedValue({
      total: 250,
      pages: 3,
      current: 1,
      size: 100,
      records: [ADMIN_ROW]
    })

    const roleTab = wrapper.findAll('.admin-user__tab').find((t) => t.text() === '管理员')
    await roleTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('角色筛选结果可能不完整')
    expect(wrapper.text()).toContain('250')
    expect(wrapper.text()).toContain('管理员小张')
  })

  // ---------------- 5.2.2 补丁项 ----------------

  it('⑬ 未选角色时请求参数完全不变：仍是服务端分页，且绝不带 role / size', async () => {
    const { wrapper } = await mountPage()

    // 首次加载（未选角色、无关键字）
    const first = getUserListMock.mock.calls[0][0]
    expect(first).toMatchObject({ status: null, page: 1 })
    expect('role' in first).toBe(false) // 后端 AdminUserQuery 没有 role 字段，绝不能凭空多传
    expect(first.size).toBeUndefined() // size 由 api 层补默认值（ADMIN_PAGE_SIZE），页面不传

    // 输入关键字搜索 → 依旧服务端分页（page=1），依旧不带 role / size
    await wrapper.find('.admin-user__search input').setValue('zhou')
    await wrapper.find('.admin-user__search button').trigger('click')
    await flushPromises()

    const second = getUserListMock.mock.calls[1][0]
    expect(second).toMatchObject({ status: null, keyword: 'zhou', page: 1 })
    expect('role' in second).toBe(false)
    expect(second.size).toBeUndefined()
  })

  it('⑭ 「用户」列只有头像+昵称，ID 移入 el-tooltip 且是完整值（副行已删除）', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.find('.admin-user__name').text()).toBe('数院小周')
    expect(wrapper.find('.admin-user__avatar').text()).toBe('数') // 昵称首字
    // 副行删除后，「用户」列不再常驻 ID
    expect(wrapper.find('.admin-user__meta').exists()).toBe(false)

    // ID 在悬浮提示里，且必须是完整 ID（不是缩写）。
    // 这里按"所有 tooltip 的 content 里存在 ID: 26"断言，不依赖 DOM 位置 ——
    // el-table 的 .hidden-columns 影子副本也会渲染一份 tooltip（其 row 是空对象 → ID: undefined）。
    const tipContents = wrapper.findAllComponents(ElTooltip).map((t) => String(t.props('content')))
    expect(tipContents).toContain('ID: 26')

    // 学号仍只在独立列出现
    expect(wrapper.find('.admin-user__username').text()).toBe('2021001')
  })
})
