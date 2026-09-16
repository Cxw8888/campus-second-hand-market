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

    /** 是否管理员（role=1），第二批做管理端页面时会用到 */
    const isAdmin = computed(() => Number(userInfo.value?.role) === 1)

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
