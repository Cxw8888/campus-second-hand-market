/**
 * C 端分类数据源（批次 5.4）
 *
 * ============================ 为什么需要这个 store ============================
 * 5.3 管理端上了分类管理（增删改查 + 迁移），用的是**实时**接口；而学生端此前一直读
 * constants.js 里硬编码的 `CATEGORIES`。后果是：管理员新增一个分类，学生会话里根本看不到，
 * 直到有人想起来去改常量。本 store 消除这个断裂。
 *
 * ============================ 数据源优先级（首屏不阻塞） ============================
 *   store.list（本会话已取到） > localStorage 缓存（cm.category.cache） > CATEGORIES 兜底
 *
 * 关键点：**组件永远不 await**。挂载时先用当前数据同步渲染一帧，再让 ensureLoaded()
 * 在后台请求；请求回来才更新 store（Vue 响应式重渲染）。所以分类请求挂起 10 秒也不会
 * 白屏，商品列表 / 搜索 / 发布等其余功能完全不受影响。
 *
 * ============================ 缓存与变更传播周期 ============================
 * 缓存介质：Pinia state（唯一数据源，组件只读）+ localStorage 双写（跨会话）。
 * 缓存键：`cm.category.cache` → `{ list: [...], loadedAt: <毫秒时间戳> }`。
 * TTL：24 小时。**未过期直接用缓存、不发请求**；过期则先用缓存渲染、后台再拉一次。
 *
 * 于是「管理员改分类 → 学生端可见」的最长延迟 = 24 小时（或学生手动刷新页面触发
 * 未过期分支之外的任何一次重新加载）。这是**刻意选定的权衡**：分类是低频变更数据，
 * 每次切页都打接口换不来什么，反而放大弱网下的抖动。已建议记入 PROJECT_CONTEXT.md。
 *
 * ============================ 字段结构对齐（绝不外泄给组件） ============================
 * CATEGORIES（constants.js）: { id: Number, name: String }               ← 无 sort
 * 接口 CategoryVO:           { id: String, name: String, sort: Number }  ← id 是 Long→String
 *
 * 两者**字段名一致（id / name），但 id 类型不一致**，且兜底数据没有 sort。
 * 所以本 store 是唯一的归一化出口，保证组件拿到的永远是
 *   `{ id: String, name: String, sort: Number }`
 * —— 与项目「id 全程字符串」的约定一致（**不做 Number(id) 这类数字转换**），
 * 组件层因此完全不需要感知数据来自接口还是兜底。
 *
 * 若接口返回的字段既没有 id/name 也没有已知别名 → 整份数据视为无效，保持兜底渲染，
 * 仅 DEV 下 console.error（不弹错、不白屏）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getCategoryList } from '@/api/product'
import { CATEGORIES } from '@/utils/constants'

/** localStorage 缓存键（跨会话缓存分类） */
export const CATEGORY_CACHE_KEY = 'cm.category.cache'

/** 缓存有效期：24 小时 */
export const CATEGORY_CACHE_TTL = 24 * 60 * 60 * 1000

/** 只在 DEV 下打日志，生产环境保持静默 */
const DEV = import.meta.env.DEV

/**
 * 并发去重用的在途请求。
 *
 * ⚠️ 故意放在**模块级变量**而不是 state：
 *   ① 它不是业务状态，是纯粹的调度细节，进 state 会被 devtools 序列化、被持久化插件误抓；
 *   ② Promise 放进响应式 state 会被 Vue 包一层代理，identity 变掉后 `===` 比较失效，
 *      去重就静默失效了（多个组件同时挂载会发多次请求）。
 */
let pendingPromise = null

/** 已知的字段别名：接口契约是 id/name，别名只作为容错，不作为主路径 */
const ID_KEYS = ['id', 'categoryId', 'value']
const NAME_KEYS = ['name', 'categoryName', 'label']

/** 从候选键里取出第一个有效值（空串/undefined/null 都跳过） */
function pick(row, keys) {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return null
}

/** sort 缺失时用下标兜底，保证「接口没给 sort」也不会打乱接口给的顺序 */
function toSort(value, fallback) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

/**
 * 把任意来源（接口 / localStorage / 兜底）的列表归一化成组件可直接消费的结构。
 *
 * @param {unknown} data 原始列表
 * @param {string} label 仅用于 DEV 日志，说明数据来自哪里
 * @returns {Array<{id:string,name:string,sort:number}>|null} null = 无效数据（调用方保持现状）
 */
function normalizeList(data, label) {
  // 空判断：null / 非数组 / 空数组 一律视为无效（无效数据绝不覆盖 store）
  if (!data || !Array.isArray(data) || data.length === 0) {
    if (DEV) console.warn(`[category] ${label}为空或不是数组，视为无效数据（保持现有数据）`)
    return null
  }

  const out = []
  for (const row of data) {
    if (!row || typeof row !== 'object') {
      if (DEV) console.error(`[category] ${label}存在无法识别的条目，视为无效数据：`, row)
      return null
    }
    const id = pick(row, ID_KEYS)
    const name = pick(row, NAME_KEYS)
    // 映射失败：字段名既不是 id/name 也不是已知别名 → 整份数据作废
    if (id === null || name === null) {
      if (DEV) console.error(`[category] ${label}字段映射失败（缺 id 或 name），视为无效数据：`, row)
      return null
    }
    // id 一律字符串化：与「id 全程字符串」约定一致，且绝不 Number() 转换
    out.push({ id: String(id), name: String(name), sort: toSort(row.sort, out.length) })
  }

  // 后端契约是「按 sort 升序」（CategoryController.list），这里显式排一次保证确定性；
  // Array.prototype.sort 在现代引擎里是稳定排序，sort 相同者保持原顺序
  return out.sort((a, b) => a.sort - b.sort)
}

/**
 * 硬编码兜底列表（**不改 CATEGORIES 本身的内容**，只做归一化拷贝）
 *
 * sort 用声明顺序（0..n-1）：CATEGORIES 的书写顺序本来就是预期的展示顺序，
 * 而且它没有 sort 字段 —— 借用下标是最小侵入的对齐方式。
 */
const FALLBACK_LIST = CATEGORIES.map((cat, index) => ({
  id: String(cat.id),
  name: cat.name,
  sort: index
}))

// ------------------------------------------------------------------ localStorage
/** 读缓存；任何异常（隐私模式 / JSON 损坏 / 被别的代码写脏）都当作「没有缓存」 */
function readCache() {
  try {
    const raw = window.localStorage.getItem(CATEGORY_CACHE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    // loadedAt 必须是 Date.now() 那种毫秒数字，ISO 字符串一律不认（无法可靠比较）
    if (typeof parsed.loadedAt !== 'number' || !Number.isFinite(parsed.loadedAt)) return null
    const normalized = normalizeList(parsed.list, '本地缓存')
    if (!normalized) return null
    return { list: normalized, loadedAt: parsed.loadedAt }
  } catch (error) {
    if (DEV) console.warn('[category] 读取本地缓存失败（忽略，走接口）：', error?.message)
    return null
  }
}

/** 写缓存；写失败只影响「下次跨会话」，不影响本次渲染，所以只记日志 */
function writeCache(list, loadedAt) {
  try {
    window.localStorage.setItem(CATEGORY_CACHE_KEY, JSON.stringify({ list, loadedAt }))
  } catch (error) {
    if (DEV) console.warn('[category] 写入本地缓存失败（忽略）：', error?.message)
  }
}

export const useCategoryStore = defineStore('category', () => {
  /** 接口/缓存归一化后的分类列表；**组件不要直接读它**，请用 getList() */
  const list = ref([])
  /** 列表的取到时间（毫秒时间戳），null = 还没从接口/缓存拿到过 */
  const loadedAt = ref(null)

  /**
   * 组件取数**唯一入口**：返回列表（永远非空 —— 最差也是 CATEGORIES 兜底）
   * @returns {Array<{id:string,name:string,sort:number}>}
   */
  function getList() {
    return list.value.length > 0 ? list.value : FALLBACK_LIST
  }

  /** 时间戳是否过期（非数字一律按过期处理，宁可多请求一次） */
  function isExpired(at) {
    if (typeof at !== 'number' || !Number.isFinite(at)) return true
    return Date.now() - at > CATEGORY_CACHE_TTL
  }

  /**
   * 真正发请求并落库。**永不 reject**：
   * 请求失败 / 超时 / 返回无效数据都只是「保持当前数据」，由调用方保证不弹错。
   * silent:true → 拦截器不弹 ElMessage；6 秒超时在 api 层设置（弱网不拖着首屏）。
   */
  async function fetchAndStore() {
    try {
      const data = await getCategoryList({ silent: true })
      const normalized = normalizeList(data, '接口返回')
      if (!normalized) return getList()

      list.value = normalized
      loadedAt.value = Date.now()
      writeCache(normalized, loadedAt.value)
      return list.value
    } catch (error) {
      // 超时（ECONNABORTED）/ 500 / 断网都走这里：静默保持兜底，不弹错、不自动重试。
      // 下次组件挂载时再试一次（见 ensureLoaded）。
      if (DEV) console.warn('[category] 分类列表拉取失败（已静默，保持兜底）：', error?.message)
      return getList()
    }
  }

  /**
   * 并发去重：同一时刻只允许一个在途请求。
   * 多个组件同时挂载（首页侧边栏 + 发布页表单）→ 只发一次。
   */
  function requestOnce() {
    if (pendingPromise) return pendingPromise
    pendingPromise = fetchAndStore().finally(() => {
      pendingPromise = null
    })
    return pendingPromise
  }

  /**
   * 组件挂载时调用（**不要 await**，用当前数据先渲染）。
   *
   * 分支：
   *   · 本会话已取到 → 直接返回，不发请求
   *   · 有未过期缓存 → 用缓存，不发请求
   *   · 有已过期缓存 → 先用缓存渲染，后台请求更新
   *   · 无缓存       → 先用 CATEGORIES 兜底渲染，后台请求
   */
  async function ensureLoaded() {
    if (list.value.length > 0) return list.value

    const cached = readCache()
    if (cached) {
      list.value = cached.list
      loadedAt.value = cached.loadedAt
      if (!isExpired(cached.loadedAt)) return list.value
    }

    return requestOnce()
  }

  /** 手动强制刷新（忽略缓存；本批未接按钮，作为预留 action） */
  async function refresh() {
    return requestOnce()
  }

  return { list, loadedAt, getList, ensureLoaded, refresh }
})
