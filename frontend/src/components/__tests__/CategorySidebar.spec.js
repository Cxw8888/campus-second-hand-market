/**
 * CategorySidebar 分类数据源（批次 5.4）
 *
 * 覆盖验收场景：
 *   ① 首屏不阻塞：分类接口挂起时，侧边栏照样渲染 CATEGORIES 兜底（有内容，不是空壳）
 *   ⑧ 并发去重：两个组件同时挂载 → **只发 1 次请求**（本文件用两个真实组件实例验证，
 *      不是只测 store 内部）
 *   回归：点击选中 / 高亮仍然正常（本批只换数据源，不改交互）
 *
 * 测试卫生：store 内有模块级 pendingPromise，「接口挂起」的用例必须先 settle 再结束。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'

const getCategoryListMock = vi.fn()

vi.mock('@/api/product', () => ({
  getCategoryList: (...args) => getCategoryListMock(...args)
}))

import CategorySidebar from '@/components/CategorySidebar.vue'
import { CATEGORIES } from '@/utils/constants'

const API_ROWS = [
  { id: '1', name: '教材书籍', sort: 10 },
  { id: '2', name: '数码电子', sort: 20 },
  { id: '7', name: '乐器', sort: 70 }
]

/** 共享同一个 pinia —— 「两个组件同时挂载」的前提就是同一个 store 实例 */
function mountWith(pinia, props = {}) {
  return mount(CategorySidebar, {
    props,
    global: { plugins: [pinia, ElementPlus], stubs: { transition: false } }
  })
}

/** 侧边栏渲染出的分类名（跳过第一项「全部商品」） */
const namesOf = (wrapper) => wrapper.findAll('.category-sidebar__name').map((n) => n.text())

describe('CategorySidebar · 分类数据源（批次 5.4）', () => {
  let pinia

  beforeEach(() => {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    document.body.innerHTML = ''
    window.localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    getCategoryListMock.mockReset().mockResolvedValue(API_ROWS)
  })

  afterEach(() => {
    document.body.innerHTML = ''
    window.localStorage.clear()
  })

  it('场景1：接口挂起 → 侧边栏立刻渲染 CATEGORIES 兜底，不白屏', async () => {
    let resolveApi
    getCategoryListMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveApi = resolve
        })
    )

    const wrapper = mountWith(pinia)
    await flushPromises()

    // 「全部商品」+ 6 个兜底分类都在 DOM 里
    expect(namesOf(wrapper)).toEqual(['全部商品', ...CATEGORIES.map((c) => c.name)])

    resolveApi(API_ROWS)
    await flushPromises()
    // 接口回来后自动重渲染成接口数据（乐器是兜底里没有的分类）
    expect(namesOf(wrapper)).toEqual(['全部商品', '教材书籍', '数码电子', '乐器'])
  })

  it('场景8：两个组件同时挂载 → 只发 1 次分类请求', async () => {
    let resolveApi
    getCategoryListMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveApi = resolve
        })
    )

    // 同一次挂载里两个侧边栏（真实场景：首页侧边栏 + 发布页表单，或同一页两个实例）
    const first = mountWith(pinia)
    const second = mountWith(pinia)
    await flushPromises()

    expect(getCategoryListMock).toHaveBeenCalledTimes(1)

    resolveApi(API_ROWS)
    await flushPromises()

    // 两个实例都拿到同一份数据
    expect(namesOf(first)).toEqual(['全部商品', '教材书籍', '数码电子', '乐器'])
    expect(namesOf(second)).toEqual(namesOf(first))
    expect(getCategoryListMock).toHaveBeenCalledTimes(1)
  })

  it('回归：点击分类 emit 分类 id（字符串，不做数字转换），并按 modelValue 高亮', async () => {
    const wrapper = mountWith(pinia)
    await flushPromises()

    const items = wrapper.findAll('.category-sidebar__item')
    expect(items.length).toBe(1 + API_ROWS.length)

    await items[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')[0]).toEqual(['1'])

    // 高亮跟随 modelValue（同一个字符串 id 才高亮）
    await wrapper.setProps({ modelValue: '1' })
    expect(wrapper.findAll('.category-sidebar__item')[1].classes()).toContain('is-active')
    expect(wrapper.findAll('.category-sidebar__item')[2].classes()).not.toContain('is-active')
  })
})
