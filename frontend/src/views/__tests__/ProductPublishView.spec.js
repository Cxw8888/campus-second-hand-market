/**
 * 回归测试：发布商品页「发布商品」按钮的防连点
 *
 * 为什么这条测试有价值：
 *   发布页的上锁必须在 `await formRef.validate()` **之前**同步置位。
 *   如果谁把它挪到校验之后（就像下单页当初那样），快速连点会在任何一次置位之前
 *   全部穿过入口守卫 —— 于是同一次点击风暴发出多个发布请求，用户会重复发布同一件商品。
 *
 * 关键手法：让 createProduct 返回一个**一直挂起**的 Promise。
 *   否则 mock 会立刻 resolve，锁在 await 之间被释放，第 2 次点击又能重新提交，
 *   测试就测不出「锁有没有生效」了（真实网络请求本来就有耗时，这样更贴近现实）。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

// ---------------- 接口打桩 ----------------
const createProductMock = vi.fn()
const getCategoryListMock = vi.fn()

vi.mock('@/api/product', () => ({
  createProduct: (...args) => createProductMock(...args),
  getProductDetail: vi.fn(),
  getMyProducts: vi.fn(),
  updateProduct: vi.fn(),
  offShelfProduct: vi.fn(),
  deleteProduct: vi.fn(),
  getProductList: vi.fn(),
  // 批次 5.4：ProductForm 的分类下拉改从 stores/category 取数，store 内部调这个接口。
  // 不在这里补上的话，store 会拿到 undefined 并静默保持 CATEGORIES 兜底 —— 能跑，
  // 但测试就不再反映真实装配，所以显式补一个真实的返回。
  getCategoryList: (...args) => getCategoryListMock(...args)
}))

// ProductForm → ImageUploader → api/upload，一并打桩，避免真实请求
vi.mock('@/api/upload', () => ({ uploadImage: vi.fn() }))

import ProductPublishView from '@/views/ProductPublishView.vue'
import ProductForm from '@/components/ProductForm.vue'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/product/publish', name: 'product-publish', component: ProductPublishView },
      {
        path: '/product/publish-success/:productId',
        name: 'product-publish-success',
        component: { template: '<div/>' }
      },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/product/publish')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  const wrapper = mount(ProductPublishView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

/** 把表单填成一份合法数据（直接写响应式对象，避免去戳一堆 Element Plus 内部 DOM） */
function fillValidForm(wrapper) {
  const form = wrapper.findComponent(ProductForm).vm.form
  form.title = '考研数学复习全书 九成新'
  form.description = '只做了前三章笔记，后面全新'
  form.categoryId = 1
  form.price = 45
  form.stock = 1
  form.conditionLevel = 2
  form.tradeType = 1
  form.tradeLocation = '图书馆一楼大厅'
  form.imageUrls = ['/static/uploads/demo-textbook-01.png']
  return form
}

describe('ProductPublishView 发布商品防连点', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    // 分类缓存跨用例串扰会把 store 的请求短路掉（见 stores/category.js）
    window.localStorage.clear()
    vi.clearAllMocks()
    // 与后端真实返回一致：id 是字符串
    getCategoryListMock.mockResolvedValue([
      { id: '1', name: '教材书籍', sort: 10 },
      { id: '2', name: '数码电子', sort: 20 }
    ])
  })

  afterEach(() => {
    document.body.innerHTML = ''
    window.localStorage.clear()
  })

  it('快速连点 5 次「发布商品」，只发出 1 次请求', async () => {
    // 请求一直挂起 —— 模拟真实网络耗时，锁必须一直持有
    let resolveCreate
    createProductMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveCreate = resolve
        })
    )

    const { wrapper, router } = await mountPage()
    fillValidForm(wrapper)
    await flushPromises()

    const submitBtn = wrapper.find('.publish__submit')
    expect(submitBtn.exists()).toBe(true)

    // 连点 5 次，中间不做任何等待
    for (let i = 0; i < 5; i++) {
      await submitBtn.trigger('click')
    }
    await flushPromises()

    expect(createProductMock).toHaveBeenCalledTimes(1)

    // 请求未返回期间，按钮必须保持 loading（Element Plus 的 loading 同时禁用点击）
    expect(submitBtn.classes()).toContain('is-loading')

    // 请求返回后：跳转到发布成功页，并解锁
    resolveCreate({ id: '77' })
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('product-publish-success')
    expect(router.currentRoute.value.params.productId).toBe('77')
    expect(submitBtn.classes()).not.toContain('is-loading')
  })

  it('表单不合法时不发请求，且按钮会解锁（用户可以补全后重试）', async () => {
    createProductMock.mockResolvedValue({ id: '88' })

    const { wrapper } = await mountPage()
    // 故意什么都不填（缺标题 / 分类 / 价格 / 成色 / 交易方式 / 图片）
    const submitBtn = wrapper.find('.publish__submit')

    await submitBtn.trigger('click')
    await flushPromises()
    await flushPromises()

    expect(createProductMock).not.toHaveBeenCalled()
    // 关键：校验失败也要解锁，否则用户补全表单后点不动了
    expect(submitBtn.classes()).not.toContain('is-loading')

    // 补全后应该能正常提交
    fillValidForm(wrapper)
    await flushPromises()
    await submitBtn.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(createProductMock).toHaveBeenCalledTimes(1)
  })

  it('面交地点随交易方式联动：“仅面交”必填，“仅邮寄”隐藏', async () => {
    createProductMock.mockResolvedValue({ id: '99' })

    const { wrapper } = await mountPage()
    const productForm = wrapper.findComponent(ProductForm)
    const form = fillValidForm(wrapper)

    // 仅面交 + 地点为空 → 校验不通过，不发请求
    form.tradeLocation = ''
    await flushPromises()
    await wrapper.find('.publish__submit').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(createProductMock).not.toHaveBeenCalled()

    // 切成仅邮寄 → 地点字段整块隐藏，空值也不再拦提交
    form.tradeType = 2
    await flushPromises()
    expect(productForm.text()).not.toContain('面交地点')

    await wrapper.find('.publish__submit').trigger('click')
    await flushPromises()
    await flushPromises()
    expect(createProductMock).toHaveBeenCalledTimes(1)

    // 并且「仅邮寄」时不会把面交地点这种脏字段提交给后端
    const payload = createProductMock.mock.calls[0][0]
    expect(payload.tradeLocation).toBe('')
  })
})
