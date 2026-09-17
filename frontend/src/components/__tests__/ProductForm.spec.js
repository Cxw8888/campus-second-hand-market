/**
 * ProductForm 分类数据源与失效拦截（批次 5.4）
 *
 * 覆盖验收场景：
 *   ① / ⑧ 首屏不阻塞：分类接口挂起时，表单照样渲染兜底分类，不白屏
 *   ③     接口 500 → 保持兜底，**不弹 ElMessage.error**
 *   ④/⑨   表单联动：分类不在新列表中 → 不自动清空 form.categoryId、
 *          显示 el-alert、validate() 拦住提交并提示
 *   补充   接口返回新分类 → 下拉数据源跟着变（本批的核心目标）
 *
 * 测试卫生：store 里有一个模块级 pendingPromise，跨用例共用同一个模块实例，
 * 所以「接口挂起」的用例必须在结束前把请求 settle 掉，否则后续用例会捡到它。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'

// ---------------- 接口打桩 ----------------
const getCategoryListMock = vi.fn()

vi.mock('@/api/product', () => ({
  getCategoryList: (...args) => getCategoryListMock(...args)
}))

// ProductForm → ImageUploader → api/upload，一并打桩，避免真实请求
vi.mock('@/api/upload', () => ({ uploadImage: vi.fn() }))

import ProductForm from '@/components/ProductForm.vue'
import { CATEGORIES } from '@/utils/constants'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const API_ROWS = [
  { id: '1', name: '教材书籍', sort: 10 },
  { id: '2', name: '数码电子', sort: 20 },
  { id: '7', name: '乐器', sort: 70 }
]

/** 挂载表单（每个用例一个全新 pinia，避免 store 串状态） */
function mountForm() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(ProductForm, {
    global: { plugins: [pinia, ElementPlus], stubs: { transition: false } }
  })
  return { wrapper, form: wrapper.vm.form }
}

/**
 * 读组件内部的分类列表（下拉真正渲染的数据源）
 *
 * `categories` 是 <script setup> 里的 computed，没有进 defineExpose，
 * 所以走内部 setupState 读；必须**每次调用时重新读**（computed 会随 store 变化重算），
 * 不能在挂载时抓一个快照。
 */
const cats = (wrapper) => wrapper.vm.$.setupState.categories

/** 把表单填成一份合法数据（除分类外全部合法），用于验证「分类」这一道拦截 */
function fillValidForm(form, categoryId) {
  form.title = '考研数学复习全书 九成新'
  form.description = '只做了前三章笔记'
  form.categoryId = categoryId
  form.price = 45
  form.stock = 1
  form.conditionLevel = 2
  form.tradeType = 2 // 仅邮寄：面交地点整块隐藏，省掉一个必填项
  form.tradeLocation = ''
  form.imageUrls = ['/static/uploads/demo-textbook-01.png']
}

const alertOf = (wrapper) => wrapper.find('.product-form__category-alert')

describe('ProductForm · 分类数据源（批次 5.4）', () => {
  let errorSpy

  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    window.localStorage.clear()
    getCategoryListMock.mockReset().mockResolvedValue(API_ROWS)
    errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    errorSpy.mockRestore()
    document.body.innerHTML = ''
    window.localStorage.clear()
  })

  it('场景1：分类接口挂起 → 表单仍渲染 CATEGORIES 兜底分类，不白屏', async () => {
    let resolveApi
    getCategoryListMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveApi = resolve
        })
    )

    const { wrapper } = mountForm()
    await flushPromises()

    // 表单本体渲染出来了（没有因为等分类而白屏）
    expect(wrapper.text()).toContain('商品分类')
    expect(wrapper.text()).toContain('商品标题')
    // 下拉数据 = 兜底分类，字段已归一化为 id/name/sort 且 id 是字符串
    expect(cats(wrapper).map((c) => c.name)).toEqual(CATEGORIES.map((c) => c.name))
    expect(cats(wrapper).every((c) => typeof c.id === 'string' && 'sort' in c)).toBe(true)
    expect(getCategoryListMock).toHaveBeenCalledTimes(1)

    // 收尾：把挂起的请求放掉，避免污染后续用例（模块级 pendingPromise）
    resolveApi(API_ROWS)
    await flushPromises()
    expect(cats(wrapper).map((c) => c.name)).toEqual(['教材书籍', '数码电子', '乐器'])
  })

  it('场景3：接口 500 → 保持兜底，**不弹 ElMessage.error**', async () => {
    getCategoryListMock.mockRejectedValue(Object.assign(new Error('请求失败（HTTP 500）'), { code: 500 }))

    const { wrapper } = mountForm()
    await flushPromises()

    expect(cats(wrapper).map((c) => c.name)).toEqual(CATEGORIES.map((c) => c.name))
    expect(errorSpy).not.toHaveBeenCalled()
  })

  it('数据源切换：接口返回新分类 → 下拉读的是接口数据（含兜底里没有的「乐器」）', async () => {
    const { wrapper } = mountForm()
    await flushPromises()

    expect(cats(wrapper).map((c) => c.id)).toEqual(['1', '2', '7'])
    expect(cats(wrapper).map((c) => c.name)).toContain('乐器')
    expect(alertOf(wrapper).exists()).toBe(false)
  })

  it('场景9：分类不在新列表中 → 不自动清空 categoryId + el-alert 显示 + 校验不通过并提示', async () => {
    // 分类 9（服饰鞋包）已被管理员删除，接口里只剩 1 / 2 / 7
    const { wrapper, form } = mountForm()
    await flushPromises()

    // 模拟用户先填好表单，之后分类被删（组件不 await，数据是在填表期间变的）
    fillValidForm(form, '9')
    await flushPromises()

    // ① 绝不自动清空用户已选的值
    expect(form.categoryId).toBe('9')
    // ② 表单里出现失效提示
    const alert = alertOf(wrapper)
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('您选择的分类已失效，请重新选择')

    // ③ 提交前校验被拦住，并给出提示
    await expect(wrapper.vm.validate()).resolves.toBe(false)
    expect(errorSpy).toHaveBeenCalledWith('您选择的分类已失效，请重新选择')
  })

  it('场景9 补充：分类有效时无提示且校验通过（不误伤正常流程）', async () => {
    const { wrapper, form } = mountForm()
    await flushPromises()

    fillValidForm(form, '7')
    await flushPromises()

    expect(alertOf(wrapper).exists()).toBe(false)
    await expect(wrapper.vm.validate()).resolves.toBe(true)
    expect(errorSpy).not.toHaveBeenCalled()
  })

  it('场景9 补充：分类为空时不显示失效提示（那是「未选择」，由必填规则负责）', async () => {
    const { wrapper, form } = mountForm()
    await flushPromises()

    form.categoryId = null
    await flushPromises()

    expect(alertOf(wrapper).exists()).toBe(false)
  })

  it('编辑页回填：商品详情里的分类 id 是字符串，回填后与下拉取值同类型（不出现假失效）', async () => {
    const { wrapper, form } = mountForm()
    await flushPromises()

    wrapper.vm.setValues({ title: 'x', categoryId: '7', price: 1, stock: 1, imageUrls: [] })
    await flushPromises()

    expect(form.categoryId).toBe('7')
    expect(alertOf(wrapper).exists()).toBe(false)
  })
})
