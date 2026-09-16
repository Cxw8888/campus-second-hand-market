/**
 * 用户状态（token + userInfo）
 *
 * 持久化策略（两块分开存，各管各的，避免互相耦合）：
 *   · token   → 交给 utils/auth.js（localStorage key: cm-token）
 *               理由：axios 拦截器要读 token，而 request.js 不能 import store（会形成循环依赖），
 *               所以 token 的落盘必须放在双方都能依赖的最底层。
 *   · userInfo → 交给 pinia-plugin-persistedstate（localStorage key: cm-user）
 *               理由：这是纯展示态，放 store 里声明式持久化最省事。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '@/api/auth'
import { getProfile } from '@/api/user'
import { clearToken, getToken, setToken } from '@/utils/auth'
import { displayName as pickDisplayName } from '@/utils/format'

export const useUserStore = defineStore(
  'user',
  () => {
    // 初始值直接读 localStorage：刷新页面后 token 立刻可用，路由守卫不会误判为未登录
    const token = ref(getToken())
    const userInfo = ref(null)

    /** 是否已登录 */
    const isLoggedIn = computed(() => Boolean(token.value))

    /** 展示用昵称（昵称 → 用户名 → 同学） */
    const displayName = computed(() => pickDisplayName(userInfo.value))

    /** 是否管理员（role=1）；管理端路由守卫依赖它 */
    const isAdmin = computed(() => Number(userInfo.value?.role) === 1)

    /**
     * 角色是否已知
     *
     * 登录成功后 role 一定在（LoginVO 带 role）；但本地缓存可能缺 role：
     * 比如手工清过 cm-user、换过浏览器缓存、或者用旧版本登录过。
     * 这时候如果直接按 isAdmin=false 处理，**一个真管理员会被自己的前端拦到 403**。
     */
    const hasRole = computed(() => userInfo.value?.role !== undefined && userInfo.value?.role !== null)

    /** 并发去重：同一时刻只发一次 profile 请求（防止守卫重复触发打多个请求） */
    let profilePromise = null

    /**
     * 兜底确认身份：token 在但本地没有 role 时，向后端要一次资料把角色补回来。
     *
     * 注意字段映射：UserVO 里用户主键叫 **id**，而 LoginVO / store 里叫 **userId**，
     * 直接整体覆盖会让 userInfo.userId 变成 undefined，进而让订单详情页的
     * 「我买到的 / 我卖出的」判定全错 —— 所以这里显式把 id 映射成 userId。
     *
     * @returns {Promise<object|null>} 补全后的 userInfo（拿不到就返回 null，由调用方决定怎么处理）
     */
    async function ensureProfile() {
      if (hasRole.value) return userInfo.value
      if (!token.value) return null
      if (!profilePromise) {
        profilePromise = getProfile({ silent: true })
          .then((profile) => {
            if (profile) {
              userInfo.value = {
                ...(userInfo.value || {}),
                ...profile,
                userId: profile.id ?? userInfo.value?.userId
              }
            }
            return userInfo.value
          })
          .catch((error) => {
            // 静默失败：401 时拦截器已经清了 token 并跳登录页，这里不再重复提示
            console.warn('[user] 兜底获取用户资料失败：', error?.message)
            return null
          })
          .finally(() => {
            profilePromise = null
          })
      }
      return profilePromise
    }

    /**
     * 登录：成功后把 token 同时写入 Pinia 与 localStorage
     * @param {{ username: string, password: string }} payload
     */
    async function login(payload) {
      const data = await authApi.login(payload)
      token.value = data?.token || ''
      setToken(token.value)
      userInfo.value = {
        userId: data?.userId,
        username: data?.username,
        nickname: data?.nickname,
        avatar: data?.avatar,
        role: data?.role
      }
      return data
    }

    /**
     * 退出登录：先通知后端拉黑当前 token，再清本地状态。
     * 后端失败也要清本地——否则用户会卡在「点了退出但还是登录态」的尴尬状态。
     */
    async function logout() {
      try {
        await authApi.logout()
      } catch (error) {
        // 静默：token 可能已过期，退出动作本身不应报错打断用户
        console.warn('[user] 退出登录接口调用失败，已忽略：', error?.message)
      }
      reset()
    }

    /** 清空登录态（登出 / 401 失效时调用） */
    function reset() {
      token.value = ''
      userInfo.value = null
      clearToken()
    }

    /** 局部更新用户信息（第二批「个人中心」会用到） */
    function patchUserInfo(patch) {
      userInfo.value = { ...(userInfo.value || {}), ...(patch || {}) }
    }

    return {
      token,
      userInfo,
      isLoggedIn,
      displayName,
      isAdmin,
      hasRole,
      ensureProfile,
      login,
      logout,
      reset,
      patchUserInfo
    }
  },
  {
    // 只持久化 userInfo；token 由 utils/auth.js 单独负责
    persist: {
      key: 'cm-user',
      paths: ['userInfo']
    }
  }
)
