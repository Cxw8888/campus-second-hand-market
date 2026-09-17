/**
 * 分类数据源 store（批次 5.4）单测
 *
 * 覆盖验收场景 1-8、11、12：
 *   ① 首屏兜底不阻塞  ② 请求成功落库 + 双写  ③ 请求失败静默  ④ 超时静默不重试
 *   ⑤ 无效数据不覆盖  ⑥ 缓存命中不发请求    ⑦ 缓存过期先用后更 ⑧ 并发去重
 *   ⑪ 24 小时边界     ⑫ 字段别名映射 / 映射失败
 *
 * ⚠️ 测试卫生（本批特别注意第 9 条）：
 *   · store 里有一个**模块级** pendingPromise，跨用例复用同一个模块实例；
 *     所以每个用例都必须把请求 settle 掉，否则下一个用例会捡到上一个的 Promise。
 *   · localStorage 每个用例前 clear()，避免缓存串扰。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// ---------------- 接口打桩（store 只 import 这一个函数） ----------------
const getCategoryListMock = vi.fn()

vi.mock('@/api/product', () => ({
  getCategoryList: (...args) => getCategoryListMock(...args)
}))

import {
  CATEGORY_CACHE_KEY,
  CATEGORY_CACHE_TTL,
  useCategoryStore
} from '@/stores/category'
import { CATEGORIES } from '@/utils/constants'

/** 接口真实形状：id 是 Long→String，带 sort（2026-xx 实测后端返回） */
const API_ROWS = [
  { id: '1', name: '教材书籍', sort: 10 },
  { id: '2', name: '数码电子', sort: 20 },
  { id: '7', name: '乐器', sort: 70 }
]

/** 兜底数据的分类名（顺序即 CATEGORIES 声明顺序） */
const FALLBACK_NAMES = CATEGORIES.map((c) => c.name)

let warnSpy
let errorSpy

beforeEach(() => {
  setActivePinia(createPinia())
  window.localStorage.clear()
  getCategoryListMock.mockReset()
  getCategoryListMock.mockResolvedValue(API_ROWS)
  warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
  errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  warnSpy.mockRestore()
  errorSpy.mockRestore()
  window.localStorage.clear()
})

/** 写一份缓存（loadedAt 由调用方决定） */
function seedCache(list, loadedAt) {
  window.localStorage.setItem(CATEGORY_CACHE_KEY, JSON.stringify({ list, loadedAt }))
}

describe('stores/category · 首屏与兜底', () => {
  it('场景1：store 空 + 缓存空 → getList() 立刻返回 CATEGORIES 兜底，同时后台发出请求', async () => {
    let resolveApi
    getCategoryListMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveApi = resolve
        })
    )

    const store = useCategoryStore()
    expect(store.list).toEqual([])
    // 首屏一帧就有数据（不是空的），且字段已归一化为 id/name/sort
    expect(store.getList().map((c) => c.name)).toEqual(FALLBACK_NAMES)
    expect(store.getList().every((c) => typeof c.id === 'string')).toBe(true)

    const pending = store.ensureLoaded()
    expect(getCategoryListMock).toHaveBeenCalledTimes(1)
    // 请求还没回来，界面仍然是兜底数据（不阻塞、不白屏）
    expect(store.getList().map((c) => c.name)).toEqual(FALLBACK_NAMES)

    resolveApi(API_ROWS)
    await pending
    expect(store.getList().map((c) => c.id)).toEqual(['1', '2', '7'])
  })

  it('场景2：请求成功 → store 更新 + 写入 localStorage（loadedAt 是毫秒数字）', async () => {
    const store = useCategoryStore()
    const before = Date.now()
    await store.ensureLoaded()

    expect(store.getList()).toEqual(API_ROWS)
    expect(store.loadedAt).toBeGreaterThanOrEqual(before)
    // 必须是 silent：拦截器据此不弹 ElMessage
    expect(getCategoryListMock).toHaveBeenCalledWith({ silent: true })

    const raw = window.localStorage.getItem(CATEGORY_CACHE_KEY)
    expect(raw).toBeTruthy()
    const cached = JSON.parse(raw)
    expect(cached.list).toEqual(API_ROWS)
    expect(typeof cached.loadedAt).toBe('number')
    expect(cached.loadedAt).toBe(store.loadedAt)
    // 明确排除 ISO 字符串：必须是 Date.now() 那种可直接相减的毫秒数字
    expect(typeof cached.loadedAt).toBe('number')
    expect(Date.now() - cached.loadedAt).toBeLessThan(5000)
  })

  it('场景5：接口返回空数组 / null / 非数组 → 保持兜底，且 console.warn 被调用', async () => {
    getCategoryListMock
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce({ code: 200, data: [] })

    const store = useCategoryStore()
    await store.ensureLoaded()
    await store.ensureLoaded()
    await store.ensureLoaded()

    expect(store.list).toEqual([])
    expect(store.getList().map((c) => c.name)).toEqual(FALLBACK_NAMES)
    expect(warnSpy).toHaveBeenCalledTimes(3)
    // 无效数据不落缓存
    expect(window.localStorage.getItem(CATEGORY_CACHE_KEY)).toBeNull()
  })

  it('场景5 补充：已有数据时接口返回空数组 → 不覆盖 store（保持上一份有效数据）', async () => {
    const store = useCategoryStore()
    await store.ensureLoaded()
    expect(store.getList()).toEqual(API_ROWS)

    getCategoryListMock.mockResolvedValueOnce([])
    await store.refresh()

    expect(store.getList()).toEqual(API_ROWS)
    expect(warnSpy).toHaveBeenCalled()
  })
})

describe('stores/category · 失败与超时静默', () => {
  it('场景3：请求失败（500）→ 保持兜底，不抛异常（永不 reject）', async () => {
    const bizError = Object.assign(new Error('请求失败（HTTP 500）'), { code: 500 })
    getCategoryListMock.mockRejectedValue(bizError)

    const store = useCategoryStore()
    await expect(store.ensureLoaded()).resolves.toBeDefined()

    expect(store.getList().map((c) => c.name)).toEqual(FALLBACK_NAMES)
    expect(window.localStorage.getItem(CATEGORY_CACHE_KEY)).toBeNull()
    expect(warnSpy).toHaveBeenCalled()
  })

  it('场景4：请求超时 → 保持兜底、只发 1 次（不自动重试）；下次挂载时再试', async () => {
    const timeoutError = Object.assign(new Error('请求超时，请稍后重试'), { name: 'NetworkError' })
    getCategoryListMock.mockRejectedValue(timeoutError)

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(store.getList().map((c) => c.name)).toEqual(FALLBACK_NAMES)
    expect(getCategoryListMock).toHaveBeenCalledTimes(1)

    // 「不自动重试」：等一段时间也不会冒出第二次请求
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(getCategoryListMock).toHaveBeenCalledTimes(1)

    // 「下次挂载再试」：再调一次 ensureLoaded 才会重新发请求
    getCategoryListMock.mockResolvedValueOnce(API_ROWS)
    await store.ensureLoaded()
    expect(getCategoryListMock).toHaveBeenCalledTimes(2)
    expect(store.getList()).toEqual(API_ROWS)
  })
})

describe('stores/category · 缓存', () => {
  it('场景6：缓存命中且未过期 → 直接用缓存，不发请求', async () => {
    seedCache(API_ROWS, Date.now() - 1000)

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(getCategoryListMock).not.toHaveBeenCalled()
    expect(store.getList()).toEqual(API_ROWS)
  })

  it('场景7：缓存命中但已过期（>24h）→ 先用缓存渲染，后台请求更新', async () => {
    const staleList = [{ id: '9', name: '旧分类', sort: 1 }]
    seedCache(staleList, Date.now() - CATEGORY_CACHE_TTL - 60_000)

    let resolveApi
    getCategoryListMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveApi = resolve
        })
    )

    const store = useCategoryStore()
    const pending = store.ensureLoaded()

    // 请求还没回来：已经是缓存数据，而不是 CATEGORIES 兜底
    expect(store.getList()).toEqual(staleList)
    expect(getCategoryListMock).toHaveBeenCalledTimes(1)

    resolveApi(API_ROWS)
    await pending
    expect(store.getList()).toEqual(API_ROWS)
    expect(JSON.parse(window.localStorage.getItem(CATEGORY_CACHE_KEY)).list).toEqual(API_ROWS)
  })

  it('场景11：23h59m 命中缓存（不发请求）', async () => {
    seedCache(API_ROWS, Date.now() - (CATEGORY_CACHE_TTL - 60_000))

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(getCategoryListMock).not.toHaveBeenCalled()
    expect(store.getList()).toEqual(API_ROWS)
  })

  it('场景11：24h01m 视为过期（触发请求）', async () => {
    seedCache(API_ROWS, Date.now() - (CATEGORY_CACHE_TTL + 60_000))

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(getCategoryListMock).toHaveBeenCalledTimes(1)
  })

  it('缓存损坏 / loadedAt 不是数字 → 当作没有缓存，走接口', async () => {
    window.localStorage.setItem(CATEGORY_CACHE_KEY, '{"list":[{"id":"1","name":"x"}],"loadedAt":"2026-01-01T00:00:00Z"}')

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(getCategoryListMock).toHaveBeenCalledTimes(1)
    expect(store.getList()).toEqual(API_ROWS)
  })
})

describe('stores/category · 并发去重与字段映射', () => {
  it('场景8：两个组件同时挂载 → 只发 1 次请求', async () => {
    let resolveApi
    getCategoryListMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveApi = resolve
        })
    )

    const store = useCategoryStore()
    // 两个组件（首页侧边栏 / 发布页表单）在同一个 tick 里各自 ensureLoaded()
    const first = store.ensureLoaded()
    const second = store.ensureLoaded()

    expect(getCategoryListMock).toHaveBeenCalledTimes(1)

    resolveApi(API_ROWS)
    await Promise.all([first, second])
    expect(store.getList()).toEqual(API_ROWS)

    // 请求结束后再去重锁必须已释放，否则后续刷新会永远拿旧 Promise
    getCategoryListMock.mockResolvedValueOnce([{ id: '3', name: '生活用品', sort: 30 }])
    await store.refresh()
    expect(getCategoryListMock).toHaveBeenCalledTimes(2)
  })

  it('场景12：接口字段用别名（categoryId/categoryName）→ store 归一化为 id/name', async () => {
    getCategoryListMock.mockResolvedValue([
      { categoryId: '7', categoryName: '乐器', sort: 70 },
      { value: '8', label: '乐器配件', sort: 80 }
    ])

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(store.getList()).toEqual([
      { id: '7', name: '乐器', sort: 70 },
      { id: '8', name: '乐器配件', sort: 80 }
    ])
    expect(errorSpy).not.toHaveBeenCalled()
  })

  it('场景12：字段名既不是 id/name 也不是已知别名 → 视为无效，保持兜底 + console.error', async () => {
    getCategoryListMock.mockResolvedValue([{ pk: '7', title: '乐器' }])

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(store.list).toEqual([])
    expect(store.getList().map((c) => c.name)).toEqual(FALLBACK_NAMES)
    expect(errorSpy).toHaveBeenCalled()
    expect(window.localStorage.getItem(CATEGORY_CACHE_KEY)).toBeNull()
  })

  it('补充：id 全程字符串（数字型 id 也被字符串化，不做 Number 转换）+ 按 sort 升序', async () => {
    getCategoryListMock.mockResolvedValue([
      { id: 30, name: '生活用品', sort: 30 },
      { id: 10, name: '教材书籍', sort: 10 }
    ])

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(store.getList()).toEqual([
      { id: '10', name: '教材书籍', sort: 10 },
      { id: '30', name: '生活用品', sort: 30 }
    ])
  })

  it('补充：接口没给 sort 时按下标兜底，不打乱接口给的顺序', async () => {
    getCategoryListMock.mockResolvedValue([
      { id: '5', name: '服饰鞋包' },
      { id: '6', name: '其他闲置' }
    ])

    const store = useCategoryStore()
    await store.ensureLoaded()

    expect(store.getList()).toEqual([
      { id: '5', name: '服饰鞋包', sort: 0 },
      { id: '6', name: '其他闲置', sort: 1 }
    ])
  })
})
