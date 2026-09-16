/**
 * 管理端 · 审计日志页（第五批 5.3）—— 只读页
 *
 * 重点守：
 *   ① **只读**：页面里不能有任何写操作按钮（需求明确）
 *   ② 时间筛选默认留空 = 全部（不设"近 7 天"默认），且格式固定 YYYY-MM-DD HH:mm:ss 字符串
 *   ③ operationType 走 11 个字符串枚举精确匹配；未知值也不能崩（回显原值）
 *   ④ 操作人显示 operatorName（后端实际是用户 ID 字符串）→ 兜底 operatorId
 *   ⑤ 目标列只显示「类型:id」，**不做跳转**（管理端三个页面都不支持按 id 查询）
 *   ⑥ 5 态：loading / forbidden / error / empty / success
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import { ElDatePicker } from 'element-plus'

const getAuditLogListMock = vi.fn()

vi.mock('@/api/admin', () => ({
  getAuditLogList: (...a) => getAuditLogListMock(...a),
  // 本页用不到，保持模块形状完整
  getAdminUserList: vi.fn(),
  banUser: vi.fn(),
  unbanUser: vi.fn(),
  getAdminProductList: vi.fn(),
  auditProduct: vi.fn(),
  forceOfflineProduct: vi.fn(),
  getAdminOrderList: vi.fn(),
  unfreezeOrder: vi.fn(),
  forceRefundOrder: vi.fn(),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  deleteCategory: vi.fn(),
  migrateCategory: vi.fn()
}))

import AdminAuditLogView from '@/views/admin/AdminAuditLogView.vue'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const LOGS = [
  {
    id: '1001',
    operatorId: '1',
    operatorName: '1', // 后端写入的是用户 ID 的字符串（AdminAuditService:62）
    operationType: 'APPROVE_PRODUCT',
    targetType: 'PRODUCT',
    targetId: '30',
    result: 1,
    detail: '审核结果=通过, 原因=null',
    ip: '127.0.0.1',
    createTime: '2026-09-17 10:00:00'
  },
  {
    id: '1002',
    operatorId: '1',
    operatorName: '1',
    operationType: 'BAN_USER',
    targetType: 'USER',
    targetId: '26',
    result: 1,
    detail: '封禁用户, 下架商品并冻结订单数=2',
    ip: '127.0.0.1',
    createTime: '2026-09-17 10:05:00'
  }
]

async function mountPage(records = LOGS) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/audit-log', name: 'admin-audit-log', component: AdminAuditLogView },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/admin/audit-log')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  getAuditLogListMock.mockResolvedValue({
    total: records.length,
    pages: 1,
    current: 1,
    size: 10,
    records
  })

  const wrapper = mount(AdminAuditLogView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

describe('AdminAuditLogView 审计日志', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('① 默认查询：不传 operationType / startTime / endTime（时间留空 = 全部）', async () => {
    await mountPage()

    expect(getAuditLogListMock).toHaveBeenCalledTimes(1)
    const [params, options] = getAuditLogListMock.mock.calls[0]
    expect(params.page).toBe(1)
    expect(params.operationType).toBeUndefined()
    expect(params.startTime).toBeUndefined() // 需求：默认留空 = 全部，不设"近 7 天"
    expect(params.endTime).toBeUndefined()
    expect(options).toEqual({ silent: true })
  })

  it('② 列表渲染：操作人 / 操作类型 / 目标 / 结果 / 详情 / 时间', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('商品审核通过') // 11 个枚举的中文标签
    expect(wrapper.text()).toContain('封禁用户')
    expect(wrapper.text()).toContain('商品:30') // 目标列 = 类型:id
    expect(wrapper.text()).toContain('用户:26')
    expect(wrapper.text()).toContain('成功')
    expect(wrapper.text()).toContain('审核结果=通过')
    expect(wrapper.text()).toContain('2026-09-17')
    expect(wrapper.text()).toContain('共 2 条记录')
  })

  it('③ 只读页：表格里没有任何操作按钮', async () => {
    const { wrapper } = await mountPage()

    // 行内按钮 = 0（el-table 的 hidden-columns 影子副本也不算）
    expect(wrapper.findAll('.el-table__body button').length).toBe(0)
    // 页面上只有筛选用按钮（查询），没有新增/编辑/删除之类
    const texts = wrapper.findAll('button').map((b) => b.text())
    expect(texts.some((t) => t.includes('新增') || t.includes('编辑') || t.includes('删除'))).toBe(false)
  })

  it('④ operationType 筛选：选中后参数带上该枚举值，并回到第 1 页', async () => {
    const { wrapper } = await mountPage()

    // 直接调组件的筛选入口：先选下拉（jsdom 里 el-select 需点选项）
    await wrapper.find('.admin-audit__select').trigger('click')
    await flushPromises()
    const options = document.querySelectorAll('.el-select-dropdown__item')
    expect(options.length).toBe(11) // 11 个枚举，一个不多一个不少
    options[0].dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(getAuditLogListMock).toHaveBeenCalledTimes(2)
    expect(getAuditLogListMock.mock.calls[1][0]).toMatchObject({ page: 1 })
    expect(getAuditLogListMock.mock.calls[1][0].operationType).toBeTruthy()
  })

  it('⑤ 时间筛选：datetimerange + 值格式固定 YYYY-MM-DD HH:mm:ss，快捷项就位', async () => {
    const { wrapper } = await mountPage()

    const picker = wrapper.findComponent(ElDatePicker)
    expect(picker.exists()).toBe(true)
    expect(picker.props('type')).toBe('datetimerange')
    // 后端 @DateTimeFormat 固定 yyyy-MM-dd HH:mm:ss → 前端必须用同一格式（绝不能传时间戳）
    expect(picker.props('valueFormat')).toBe('YYYY-MM-DD HH:mm:ss')

    // 快捷项：今天 / 近 7 天 / 近 30 天（打开面板后可见，面板 teleport 到 body）
    await wrapper.find('.admin-audit__date input').trigger('click')
    await flushPromises()
    const shortcuts = [...document.querySelectorAll('.el-picker-panel__shortcut')]
    expect(shortcuts.map((s) => s.textContent.trim())).toEqual(['今天', '近 7 天', '近 30 天'])

    // 选中范围后（模拟选择器提交 v-model + change）→ 参数里带上同格式字符串，并回到第 1 页
    const range = ['2026-09-01 00:00:00', '2026-09-30 23:59:59']
    picker.vm.$emit('update:modelValue', range)
    picker.vm.$emit('change', range)
    await flushPromises()

    const last = getAuditLogListMock.mock.calls.at(-1)[0]
    expect(last.startTime).toBe('2026-09-01 00:00:00')
    expect(last.endTime).toBe('2026-09-30 23:59:59')
    expect(typeof last.startTime).toBe('string') // 字符串，不是时间戳
    expect(last.page).toBe(1)
  })

  it('⑥ 空结果：有筛选时文案点明"操作类型是精确匹配"', async () => {
    const { wrapper } = await mountPage([])

    expect(wrapper.text()).toContain('还没有审计记录')

    await wrapper.find('.admin-audit__select').trigger('click')
    await flushPromises()
    document
      .querySelectorAll('.el-select-dropdown__item')[0]
      .dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(wrapper.text()).toContain('没有匹配的审计记录')
    expect(wrapper.text()).toContain('精确匹配')
  })

  it('⑦ 加载失败 → 错误态可重试；403 → 独立的无权限态', async () => {
    getAuditLogListMock.mockRejectedValueOnce(new Error('无法连接后端服务'))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('加载失败，请重试')
    expect(wrapper.find('.admin-audit__skeleton').exists()).toBe(false)

    getAuditLogListMock.mockRejectedValueOnce({ code: 403, message: '无权限访问' })
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重新加载'))
    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('没有管理权限')
    expect(wrapper.find('.admin-audit__table').exists()).toBe(false)
  })

  it('⑧ 操作人优先显示 operatorName，缺失时兜底 operatorId；未知 operationType 不崩', async () => {
    const { wrapper } = await mountPage([
      { ...LOGS[0], operatorName: null, operatorId: '7' }, // 没有 name → 用 id
      { ...LOGS[1], operationType: 'UNKNOWN_OP' } // 后端将来新增枚举时不至于白屏
    ])

    expect(wrapper.text()).toContain('7')
    expect(wrapper.text()).toContain('UNKNOWN_OP') // 未知类型原样回显
  })
})
