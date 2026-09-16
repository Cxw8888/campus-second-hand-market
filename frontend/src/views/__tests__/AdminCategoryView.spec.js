/**
 * 管理端 · 分类管理页（第五批 5.3）
 *
 * 重点守：
 *   ① 关联商品数来自「管理员调 /product/list?categoryId&size=1 的 total」（CategoryVO 没有 productCount）
 *      —— 取不到必须显示「—」，**绝不写 0**
 *   ② 分类名重复（后端 code=100）时弹窗不关，用户可直接改名重试
 *   ③ 删除遇 **208**（分类下有商品）→ **弹迁移引导弹窗**，不静默刷新；迁移成功后自动删除源分类
 *   ④ 新增/编辑是 el-dialog + el-form + rules；提交锁必须在第一个 await 之前置位（防连点）
 *   ⑤ 5 态：loading / forbidden(403) / error / empty / success
 *
 * ⚠️ 两条测试环境约定（5.3 实测踩到）：
 *   · el-dialog 的 DOM 在 **wrapper 内**（EP 默认 append-to-body=false，而 test-utils 的挂载容器是游离 div）
 *     → 必须用 wrapper.find，不能 document.querySelector（ElMessageBox 才在 body 上）
 *   · el-table 会把每列模板再渲染一份到 .hidden-columns（row 是空对象）
 *     → 行内按钮一律用 findRowButton() 限定 .el-table__body
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

const getCategoryListMock = vi.fn()
const getProductListMock = vi.fn()
const createCategoryMock = vi.fn()
const updateCategoryMock = vi.fn()
const deleteCategoryMock = vi.fn()
const migrateCategoryMock = vi.fn()

vi.mock('@/api/admin', () => ({
  createCategory: (...a) => createCategoryMock(...a),
  updateCategory: (...a) => updateCategoryMock(...a),
  deleteCategory: (...a) => deleteCategoryMock(...a),
  migrateCategory: (...a) => migrateCategoryMock(...a),
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
  getAuditLogList: vi.fn()
}))

vi.mock('@/api/product', () => ({
  getCategoryList: (...a) => getCategoryListMock(...a),
  getProductList: (...a) => getProductListMock(...a),
  getProductDetail: vi.fn(),
  createProduct: vi.fn(),
  updateProduct: vi.fn(),
  getMyProducts: vi.fn(),
  offShelfProduct: vi.fn(),
  deleteProduct: vi.fn()
}))

import AdminCategoryView from '@/views/admin/AdminCategoryView.vue'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const CATEGORIES = [
  { id: '1', name: '教材书籍', sort: 1 },
  { id: '2', name: '数码电子', sort: 2 },
  { id: '3', name: '其它闲置', sort: 99 }
]

async function mountPage(categories = CATEGORIES) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/category', name: 'admin-category', component: AdminCategoryView },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/admin/category')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  getCategoryListMock.mockResolvedValue(categories)
  // 分类 1 有 5 件、分类 2 有 0 件、分类 3 取不到（模拟失败）
  getProductListMock.mockImplementation(async ({ categoryId }) => {
    if (categoryId === '1') return { total: '5', records: [] }
    if (categoryId === '2') return { total: '0', records: [] }
    throw new Error('取数失败')
  })

  const wrapper = mount(AdminCategoryView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

/** 行内按钮：限定在真实数据行内（避开 el-table 的 .hidden-columns 影子副本） */
const findRowButton = (wrapper, text) =>
  wrapper.findAll('.el-table__body button').find((node) => node.text().includes(text))

/** el-dialog 在 wrapper 内（见文件头说明），不要用 document.querySelector */
const dialogEl = (wrapper) => wrapper.find('.el-dialog')
const dialogNameInput = (wrapper) => wrapper.find('.el-dialog input.el-input__inner')
const dialogSaveBtn = (wrapper) => wrapper.find('.el-dialog__footer .el-button--primary')

async function openCreateDialog(wrapper) {
  await wrapper.findAll('button').find((b) => b.text().includes('新增分类')).trigger('click')
  await flushPromises()
}

async function fillAndSave(wrapper, name) {
  await dialogNameInput(wrapper).setValue(name)
  await flushPromises()
  await dialogSaveBtn(wrapper).trigger('click')
  await flushPromises()
  await flushPromises()
}

/** ElMessageBox 是 append 到 body 的，所以这个用 document 查 */
const confirms = () => document.querySelector('.el-message-box__btns .el-button--primary')

describe('AdminCategoryView 分类管理', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
    createCategoryMock.mockResolvedValue({ id: '9' })
    updateCategoryMock.mockResolvedValue(null)
    deleteCategoryMock.mockResolvedValue(null)
    migrateCategoryMock.mockResolvedValue({ movedCount: 3 })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('① 列表渲染分类名 / 排序 / 真实关联商品数（取自 /product/list 的 total）', async () => {
    const { wrapper } = await mountPage()

    expect(wrapper.text()).toContain('教材书籍')
    expect(wrapper.text()).toContain('数码电子')
    expect(wrapper.text()).toContain('共 3 个分类')

    expect(getProductListMock).toHaveBeenCalledWith(
      expect.objectContaining({ categoryId: '1', page: 1, size: 1, silent: true })
    )
    expect(wrapper.text()).toContain('5 件')
    expect(wrapper.text()).toContain('0 件')
  })

  it('② 取不到商品数时显示「—」而不是 0（不塞假数据）', async () => {
    const { wrapper } = await mountPage()

    const rows = wrapper.findAll('.el-table__body .el-table__row')
    expect(rows[2].text()).toContain('—')
    expect(rows[2].find('.admin-category__count-num').exists()).toBe(false)
  })

  it('③ 空列表 → 空状态，并给出「新增第一个分类」入口', async () => {
    const { wrapper } = await mountPage([])

    expect(wrapper.text()).toContain('还没有任何分类')
    expect(wrapper.text()).toContain('新增第一个分类')
    expect(wrapper.find('.admin-category__table').exists()).toBe(false)
  })

  it('④ 加载失败 → 错误态可重试；403 → 独立的无权限态', async () => {
    getCategoryListMock.mockRejectedValueOnce(new Error('无法连接后端服务'))
    const { wrapper } = await mountPage()
    expect(wrapper.text()).toContain('加载失败，请重试')
    expect(wrapper.find('.admin-category__skeleton').exists()).toBe(false)

    getCategoryListMock.mockRejectedValueOnce({ code: 403, message: '无权限访问' })
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重新加载'))
    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('没有管理权限')
    expect(wrapper.find('.admin-category__table').exists()).toBe(false)
  })

  it('⑤ 新增：弹窗表单提交 → name 已 trim、sort 是数字；成功后关闭并刷新列表', async () => {
    const { wrapper } = await mountPage()
    const before = getCategoryListMock.mock.calls.length

    await openCreateDialog(wrapper)
    expect(dialogEl(wrapper).exists()).toBe(true)

    await fillAndSave(wrapper, '  乐器  ')

    expect(createCategoryMock).toHaveBeenCalledTimes(1)
    expect(createCategoryMock).toHaveBeenCalledWith({ name: '乐器', sort: 0 })
    expect(getCategoryListMock.mock.calls.length).toBe(before + 1) // 保存后刷新

    // 关闭判定：EP 的 dialog 关闭是**过渡动画**，关闭瞬间 overlay 还带 `is-closing` 且元素仍在
    // （实测：立刻断言 exists()===false 或 display:none 都会红）。所以分两步：
    // ① 断言确实进入了关闭态 ② 等过渡结束后确认真的隐藏
    // 关闭判定：EP 的 dialog 关闭是**过渡动画**，只能断言"稳定后的终态"。
    // ⚠️ 不要断言过渡中间态（例如 .el-overlay-dialog 上的 is-closing 类）：
    //    单独跑该文件时能命中，并发跑（全量 12 个文件）时断言时刻早于 Vue 的过渡帧 → 随机失败。
    //    这类"时序相关断言"是 flaky 的典型来源，本批实测踩到过一次。
    await new Promise((resolve) => setTimeout(resolve, 500))
    await flushPromises()
    const overlayAfter = wrapper.find('.el-overlay')
    const dialogAfter = wrapper.find('.el-dialog')
    const hidden =
      (!overlayAfter.exists() ||
        /display:\s*none/.test(String(overlayAfter.attributes('style') || ''))) &&
      (!dialogAfter.exists() || !dialogAfter.isVisible())
    expect(hidden).toBe(true)
  })

  it('⑥ 新增：不填分类名 → 校验拦住，不发请求且弹窗不关', async () => {
    const { wrapper } = await mountPage()

    await openCreateDialog(wrapper)
    await dialogSaveBtn(wrapper).trigger('click')
    await flushPromises()
    await flushPromises()

    expect(createCategoryMock).not.toHaveBeenCalled()
    expect(dialogEl(wrapper).exists()).toBe(true)
  })

  it('⑦ 编辑：弹窗预填该行数据，保存时 id 原样当字符串传给 updateCategory', async () => {
    const { wrapper } = await mountPage()

    await findRowButton(wrapper, '编辑').trigger('click')
    await flushPromises()
    expect(dialogNameInput(wrapper).element.value).toBe('教材书籍') // 预填

    await fillAndSave(wrapper, '教材与教辅')

    expect(updateCategoryMock).toHaveBeenCalledWith('1', { name: '教材与教辅', sort: 1 })
  })

  it('⑧ 连点 5 次「保存」→ 同步锁只发 1 次请求', async () => {
    const { wrapper } = await mountPage()

    await openCreateDialog(wrapper)
    await dialogNameInput(wrapper).setValue('乐器')
    await flushPromises()

    const save = dialogSaveBtn(wrapper)
    for (let i = 0; i < 5; i++) {
      await save.trigger('click')
    }
    await flushPromises()
    await flushPromises()

    expect(createCategoryMock).toHaveBeenCalledTimes(1)
  })

  it('⑨ 分类名重复（后端 code=100）→ 弹窗保持打开，可改名重试', async () => {
    createCategoryMock.mockRejectedValueOnce({ code: 100, message: '分类名称已存在' })
    const { wrapper } = await mountPage()

    await openCreateDialog(wrapper)
    await fillAndSave(wrapper, '教材书籍')

    expect(createCategoryMock).toHaveBeenCalledTimes(1)
    expect(dialogEl(wrapper).exists()).toBe(true) // 不关，用户可以直接改名
  })

  it('⑩ 删除：二次确认后调 deleteCategory，并刷新列表', async () => {
    const { wrapper } = await mountPage()
    const before = getCategoryListMock.mock.calls.length

    await findRowButton(wrapper, '删除').trigger('click')
    await flushPromises()
    expect(document.querySelector('.el-message-box')).toBeTruthy()

    confirms().click()
    await flushPromises()
    await flushPromises()

    expect(deleteCategoryMock).toHaveBeenCalledWith('1')
    expect(getCategoryListMock.mock.calls.length).toBe(before + 1)
  })

  it('⑪ 删除遇 208（分类下有商品）→ 弹迁移引导，选目标后「迁移并删除」自动删源分类', async () => {
    deleteCategoryMock
      .mockRejectedValueOnce({ code: 208, message: '分类下存在商品，请先迁移' }) // 第一次：被 208 拒
      .mockResolvedValue(null) // 迁移后的删除

    const { wrapper } = await mountPage()

    await findRowButton(wrapper, '删除').trigger('click')
    await flushPromises()
    confirms().click()
    await flushPromises()
    await flushPromises()

    // 208 只允许弹引导：列表不应被静默刷新
    expect(getCategoryListMock.mock.calls.length).toBe(1)
    expect(dialogEl(wrapper).exists()).toBe(true)
    expect(wrapper.text()).toContain('先迁移再删除')
    expect(wrapper.text()).toContain('5 件商品') // 数量来自真实统计

    // 选择目标分类（下拉项由 el-select 渲染，teleport 到 body）
    const options = document.querySelectorAll('.el-select-dropdown__item')
    expect(options.length).toBeGreaterThan(0)
    options[0].dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    await dialogSaveBtn(wrapper).trigger('click')
    await flushPromises()
    await flushPromises()

    expect(migrateCategoryMock).toHaveBeenCalledTimes(1)
    expect(migrateCategoryMock.mock.calls[0][0]).toBe('1') // fromCategoryId 是字符串
    expect(deleteCategoryMock).toHaveBeenCalledTimes(2) // 208 那次 + 迁移后自动删除
    expect(deleteCategoryMock.mock.calls[1][0]).toBe('1')
  })
})
