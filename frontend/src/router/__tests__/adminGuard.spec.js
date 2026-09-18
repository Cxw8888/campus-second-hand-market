/**
 * 回归测试：管理端路由守卫（第五批 5.1，5.5.3 补落地页用例）
 *
 * 守的是「三件事必须分得清」，任何一条错都会造成真实事故：
 *   ① 没登录       → 跳登录页（带 redirect，回来还能接着进）
 *   ② 登录了但不是管理员 → 跳 /403 并带 from=admin（**不能**跳登录页：用户明明已经登录了）
 *   ③ role=1       → 正常放行
 *
 * 另外两条边界是这次特意补的：
 *   ④ token 在、本地缓存里却没有 role（手工清过 cm-user / 旧版本登录过）
 *      → 必须先向后端 GET /user/profile 兜底确认，否则**真管理员会被自己的前端拦到 403**；
 *      而且 UserVO 的主键叫 id（不是 userId），映射写错会让订单页的买卖判定全错，所以这里一并锁住。
 *   ⑤ 兜底请求也失败（网络异常）→ 按"不是管理员"处理（管理端权限失败要 fail closed），
 *      但绝不能停在管理端页面上。
 *
 * 5.5.3 追加：/admin 的落地页从「商品审核」改为「数据统计」（决策 2），
 * 用例 ⑩ 同时锁住"改落点"与"权限没被放开"两件事 —— 换 redirect 时最容易顺手把
 * 父级 meta 也搬走，那是真正的权限漏洞。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// ---------------- 接口打桩 ----------------
const getProfileMock = vi.fn()

vi.mock('@/api/user', () => ({
  getProfile: (...args) => getProfileMock(...args),
  // 下面这些本文件用不到，保持模块形状完整
  updateProfile: vi.fn(),
  changePassword: vi.fn(),
  changeEmail: vi.fn()
}))

import router from '@/router'
import { useUserStore } from '@/stores/user'
import { getToken, setToken } from '@/utils/auth'

const ADMIN_PATH = '/admin/product/audit'

/**
 * 每次导航都先回到首页再进管理端。
 *
 * 原因（踩过）：vue-router 对「目标与当前完全相同」的 push 会直接返回
 * NAVIGATION_DUPLICATED，**守卫根本不会被调用** —— 于是用例 ④⑤ 会因为
 * "前一个用例已经把路由停在 /admin/product/audit 了"而静默失效（断言看起来是红的，但红的原因不是被测代码）。
 */
async function gotoAdmin() {
  await router.push({ name: 'home' })
  await router.push(ADMIN_PATH)
}

describe('管理端路由守卫', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    getProfileMock.mockReset()
  })

  it('① 未登录访问 /admin/** → 跳登录页，并带上 redirect', async () => {
    await gotoAdmin()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe(ADMIN_PATH)
  })

  it('② 已登录但 role=0 → 跳 /403（不是登录页），并带 from=admin', async () => {
    setToken('student-token')
    const userStore = useUserStore()
    userStore.userInfo = { userId: '17', nickname: '买家同学', role: 0 }

    await gotoAdmin()

    expect(router.currentRoute.value.name).toBe('forbidden')
    expect(router.currentRoute.value.query.from).toBe('admin')
    // 角色已知时不应再去问后端
    expect(getProfileMock).not.toHaveBeenCalled()
  })

  it('③ role=1 → 正常进入商品审核页', async () => {
    setToken('admin-token')
    const userStore = useUserStore()
    userStore.userInfo = { userId: '1', nickname: '管理员', role: 1 }

    await gotoAdmin()

    expect(router.currentRoute.value.name).toBe('admin-product-audit')
    expect(getProfileMock).not.toHaveBeenCalled()
  })

  it('④ token 在但本地缺 role → 用 /user/profile 兜底确认后放行（并把 id 正确映射成 userId）', async () => {
    setToken('admin-token')
    const userStore = useUserStore()
    // 模拟本地缓存被清过：只剩昵称，没有 role
    userStore.userInfo = { userId: '1', nickname: '管理员' }
    getProfileMock.mockResolvedValue({ id: '99', username: 'admin', nickname: '管理员', role: 1 })

    await gotoAdmin()

    expect(getProfileMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('admin-product-audit')
    // ⚠️ UserVO 里主键叫 id，store 里叫 userId —— 映射错了订单页的「我买到的/我卖出的」就会全错
    expect(userStore.userInfo.userId).toBe('99')
    expect(userStore.isAdmin).toBe(true)
  })

  it('⑤ 兜底确认失败（网络异常）→ 按无权限处理，停在 /403 而不是管理页', async () => {
    setToken('admin-token')
    const userStore = useUserStore()
    userStore.userInfo = null
    getProfileMock.mockRejectedValue(new Error('无法连接后端服务'))

    await gotoAdmin()

    expect(getProfileMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('forbidden')
    // token 没被清掉（这不是 401），所以不该被踢到登录页
    expect(getToken()).toBe('admin-token')
  })

  it('⑥ 普通用户走正常路由不受影响（守卫不会误拦）', async () => {
    setToken('student-token')
    const userStore = useUserStore()
    userStore.userInfo = { userId: '17', nickname: '买家同学', role: 0 }

    await router.push({ name: 'home' })

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('⑦ 5.2 新增的 /admin/user 与 /admin/order 同样受角色保护（子路由继承父级 meta）', async () => {
    setToken('student-token')
    const userStore = useUserStore()
    userStore.userInfo = { userId: '17', nickname: '买家同学', role: 0 }

    await router.push({ name: 'home' })
    await router.push('/admin/user')
    expect(router.currentRoute.value.name).toBe('forbidden')

    await router.push({ name: 'home' })
    await router.push('/admin/order')
    expect(router.currentRoute.value.name).toBe('forbidden')

    // 换成管理员则两个新页面都能进（证明拦的是角色，不是路径本身）
    userStore.userInfo = { userId: '1', nickname: '管理员', role: 1 }
    await router.push({ name: 'home' })
    await router.push('/admin/order')
    expect(router.currentRoute.value.name).toBe('admin-order')

    await router.push({ name: 'home' })
    await router.push('/admin/user')
    expect(router.currentRoute.value.name).toBe('admin-user')
  })

  it('⑧ 5.3 新增的 /admin/category 与 /admin/audit-log 同样受角色保护（子路由继承父级 meta）', async () => {
    setToken('student-token')
    const userStore = useUserStore()
    userStore.userInfo = { userId: '17', nickname: '买家同学', role: 0 }

    await router.push({ name: 'home' })
    await router.push('/admin/category')
    expect(router.currentRoute.value.name).toBe('forbidden')

    await router.push({ name: 'home' })
    await router.push('/admin/audit-log')
    expect(router.currentRoute.value.name).toBe('forbidden')

    userStore.userInfo = { userId: '1', nickname: '管理员', role: 1 }
    await router.push({ name: 'home' })
    await router.push('/admin/category')
    expect(router.currentRoute.value.name).toBe('admin-category')

    await router.push({ name: 'home' })
    await router.push('/admin/audit-log')
    expect(router.currentRoute.value.name).toBe('admin-audit-log')
  })

  it('⑨ 5.5.1 新增的 /admin/dashboard 同样受角色保护，且 meta 全部从父级 /admin 继承', async () => {
    // ① 路由表层面：子路由 meta 里只有 title，权限靠父级继承 —— 这里把继承结果钉死，
    //    免得将来有人把 meta 挪到子路由却漏掉某一项（漏了 requiresAdmin 就是真漏洞）
    const resolved = router.resolve('/admin/dashboard')
    expect(resolved.name).toBe('admin-dashboard')
    expect(resolved.meta.title).toBe('数据统计')
    expect(resolved.meta.requiresAuth).toBe(true)
    expect(resolved.meta.requiresAdmin).toBe(true)
    expect(resolved.meta.admin).toBe(true)

    // ② 普通用户（role=0）→ /403（不是登录页）
    setToken('student-token')
    const userStore = useUserStore()
    userStore.userInfo = { userId: '17', nickname: '买家同学', role: 0 }
    await router.push({ name: 'home' })
    await router.push('/admin/dashboard')
    expect(router.currentRoute.value.name).toBe('forbidden')
    expect(router.currentRoute.value.query.from).toBe('admin')

    // ③ 未登录 → 登录页 + redirect（回来还能接着进）
    localStorage.clear()
    setActivePinia(createPinia())
    await router.push({ name: 'home' })
    await router.push('/admin/dashboard')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/dashboard')

    // ④ 管理员 → 正常进入
    setToken('admin-token')
    const adminStore = useUserStore()
    adminStore.userInfo = { userId: '1', nickname: '管理员', role: 1 }
    await router.push({ name: 'home' })
    await router.push('/admin/dashboard')
    expect(router.currentRoute.value.name).toBe('admin-dashboard')
  })

  it('⑩ 5.5.3：/admin 落地页 = 数据统计（redirect → admin-dashboard），且仍受管理端权限保护', async () => {
    // ---------------- ① 路由表层：redirect 指向 admin-dashboard ----------------
    //
    // ⚠️ 实测（vue-router 4.5）：`router.resolve('/admin').redirect` 是 **undefined** ——
    //    resolve() 只做路径匹配、不跟随 redirect，路由位置对象上也没有 redirect 字段。
    //    redirect 挂在**路由记录**上，两条可用途径：
    //      · router.resolve('/admin').matched 里那条带 redirect 的记录
    //      · router.getRoutes() 里 path === '/admin' 的记录
    const resolved = router.resolve('/admin')
    const redirectRecord = resolved.matched.find((record) => record.redirect)
    expect(redirectRecord).toBeTruthy()
    expect(redirectRecord.redirect).toEqual({ name: 'admin-dashboard' })

    const adminRecord = router.getRoutes().find((record) => record.path === '/admin')
    expect(adminRecord.redirect).toEqual({ name: 'admin-dashboard' })
    expect(adminRecord.redirect.name).not.toBe('admin-product-audit')

    // 落地页换了，权限 meta 一个都不能少（继承自父级 /admin）
    expect(resolved.meta.requiresAuth).toBe(true)
    expect(resolved.meta.requiresAdmin).toBe(true)
    expect(resolved.meta.admin).toBe(true)

    // ---------------- ② 真实导航：管理员访问 /admin → 落在 dashboard ----------------
    setToken('admin-token')
    const userStore = useUserStore()
    userStore.userInfo = { userId: '1', nickname: '管理员', role: 1 }

    await router.push({ name: 'home' })
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('admin-dashboard')
    expect(router.currentRoute.value.path).toBe('/admin/dashboard')

    // ---------------- ③ 换落地页不等于放开权限：普通用户仍被拦到 /403 ----------------
    userStore.userInfo = { userId: '17', nickname: '买家同学', role: 0 }
    await router.push({ name: 'home' })
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('forbidden')
    expect(router.currentRoute.value.query.from).toBe('admin')

    // ---------------- ④ 未登录仍跳登录页（回归：换 redirect 不该绕过登录拦截）----------------
    localStorage.clear()
    setActivePinia(createPinia())
    await router.push({ name: 'home' })
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('login')
    // ⚠️ 这里必须是 **/admin/dashboard** 而不是 /admin：vue-router 在**守卫之前**就把
    //    redirect 展开了，守卫看到的 to 已经是最终落点，所以 ?redirect= 带的是具体页面
    //    （登录后直接回到数据统计，不会再多跳一次 /admin —— 这是更好的行为，实测确认）
    expect(router.currentRoute.value.query.redirect).toBe('/admin/dashboard')
  })
})
