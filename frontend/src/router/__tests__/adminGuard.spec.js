/**
 * 回归测试：管理端路由守卫（第五批 5.1）
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
})
