/**
 * 我的收藏页：失效商品判定 + 不可下单拦截 + 取消收藏防连点
 *
 * 校园二手特征（本文件重点覆盖）：
 *   · **库存常为 1，售罄是高频状态** —— 所以「已售罄」必须和「已下架」「已删除」一样算失效，
 *     不能只判 status=0；
 *   · 失效商品**不隐藏**，仍要渲染出来让学生主动清理；
 *   · 失效商品不可点进详情、不给「查看详情」按钮。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

const getFavoriteListMock = vi.fn()
const removeFavoriteMock = vi.fn()

vi.mock('@/api/favorite', () => ({
  getFavoriteList: (...args) => getFavoriteListMock(...args),
  removeFavorite: (...args) => removeFavoriteMock(...args),
  addFavorite: vi.fn(),
  checkFavorite: vi.fn()
}))

import FavoriteListView from '@/views/FavoriteListView.vue'
import { resolveFavoriteState } from '@/utils/constants'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** 四类收藏项：1 个在售 + 3 种失效（已删除 / 已下架 / 已售罄） */
const ON_SALE = {
  id: '1',
  productId: '30',
  title: '考研数学复习全书 九成新',
  coverImage: '',
  price: 45,
  productStatus: 1,
  isDeleted: false,
  available: true,
  createTime: '2026-09-16 16:03:43'
}
const DELETED = {
  id: '2',
  productId: '31',
  title: '商品已不存在',
  coverImage: '',
  price: null,
  productStatus: 1,
  isDeleted: true,
  available: false,
  createTime: '2026-09-15 10:00:00'
}
const OFF_SHELF = {
  id: '3',
  productId: '32',
  title: '线性代数教材（同济第六版）',
  coverImage: '',
  price: 15,
  productStatus: 0,
  isDeleted: false,
  available: false,
  createTime: '2026-09-14 10:00:00'
}
const SOLD_OUT = {
  id: '4',
  productId: '33',
  title: 'AirPods Pro 2 国行带发票',
  coverImage: '',
  price: 899,
  productStatus: 2,
  isDeleted: false,
  available: false,
  createTime: '2026-09-13 10:00:00'
}

async function mountPage(records = [ON_SALE, DELETED, OFF_SHELF, SOLD_OUT]) {
  getFavoriteListMock.mockResolvedValue({
    total: String(records.length),
    pages: '1',
    current: '1',
    size: '12',
    records
  })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/favorite/list', name: 'favorite-list', component: FavoriteListView },
      { path: '/product/:id', name: 'product-detail', component: { template: '<div/>' } },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/favorite/list')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)

  const wrapper = mount(FavoriteListView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router }
}

describe('resolveFavoriteState：三种失效状态全覆盖', () => {
  it('isDeleted=true → 已失效 / 不可下单', () => {
    const s = resolveFavoriteState(DELETED)
    expect(s.label).toBe('已失效')
    expect(s.orderable).toBe(false)
    expect(s.deleted).toBe(true)
  })

  it('productStatus=0 → 已下架 / 不可下单', () => {
    const s = resolveFavoriteState(OFF_SHELF)
    expect(s.label).toBe('已下架')
    expect(s.orderable).toBe(false)
  })

  it('productStatus=2（售罄，校园高频）→ 已售罄 / 不可下单', () => {
    const s = resolveFavoriteState(SOLD_OUT)
    expect(s.label).toBe('已售罄')
    expect(s.orderable).toBe(false)
  })

  it('productStatus=1 且未删除 → 在售 / 可下单', () => {
    const s = resolveFavoriteState(ON_SALE)
    expect(s.label).toBe('在售')
    expect(s.orderable).toBe(true)
  })

  it('后端没下发 available 时按状态兜底推算', () => {
    expect(resolveFavoriteState({ productStatus: 1 }).orderable).toBe(true)
    expect(resolveFavoriteState({ productStatus: 2 }).orderable).toBe(false)
    expect(resolveFavoriteState({ productStatus: 0 }).orderable).toBe(false)
    expect(resolveFavoriteState({ isDeleted: true, productStatus: 1 }).orderable).toBe(false)
  })
})

describe('FavoriteListView 列表行为', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('失效商品不隐藏：四条都要渲染出来', async () => {
    const { wrapper } = await mountPage()
    const cards = wrapper.findAll('.favorite-card')
    expect(cards).toHaveLength(4)

    const text = wrapper.text()
    expect(text).toContain('在售')
    expect(text).toContain('已失效')
    expect(text).toContain('已下架')
    expect(text).toContain('已售罄')
    // 失效提示文案也在
    expect(text).toContain('已无法下单')
  })

  it('失效商品标灰且不给「查看详情」按钮，在售商品正常给', async () => {
    const { wrapper } = await mountPage()
    const cards = wrapper.findAll('.favorite-card')

    // 第 1 张是在售
    expect(cards[0].classes()).not.toContain('is-invalid')
    expect(cards[0].text()).toContain('查看详情')

    // 后 3 张都是失效
    for (const i of [1, 2, 3]) {
      expect(cards[i].classes()).toContain('is-invalid')
      expect(cards[i].text()).not.toContain('查看详情')
    }
  })

  it('点击在售商品 → 进详情页；点击失效商品 → 不跳转', async () => {
    const { wrapper, router } = await mountPage()
    const cards = wrapper.findAll('.favorite-card')

    // 先点一张失效的：不应跳转（商品已删除的详情接口会直接 204，不能把用户送进死胡同）
    await cards[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('favorite-list')

    await cards[2].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('favorite-list')

    // 再点在售的：应跳转并带上 productId
    await cards[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.params.id).toBe('30')
  })

  it('取消收藏：连点 5 次只发 1 次请求，成功后从列表移除', async () => {
    // 请求挂起，保证同步锁一直被持有（真实网络本来就有耗时）
    let resolveRemove
    removeFavoriteMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveRemove = resolve
        })
    )

    const { wrapper } = await mountPage()
    expect(wrapper.findAll('.favorite-card')).toHaveLength(4)

    // 全部卡片共 4 个「取消收藏」按钮，取第一个（在售那条）
    const cancelBtns = wrapper.findAll('.favorite-card__actions .el-button--danger')
    expect(cancelBtns.length).toBe(4)

    for (let i = 0; i < 5; i++) {
      await cancelBtns[0].trigger('click')
    }
    await flushPromises()
    expect(removeFavoriteMock).toHaveBeenCalledTimes(1)

    // 请求返回后该条被移除，其余保持不变
    resolveRemove()
    await flushPromises()
    await flushPromises()
    expect(wrapper.findAll('.favorite-card')).toHaveLength(3)
    expect(wrapper.text()).not.toContain('考研数学复习全书 九成新')
  })
})
