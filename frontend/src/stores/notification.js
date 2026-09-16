/**
 * 站内信未读数（全局状态 + 30 秒轮询）
 *
 * 为什么放在 Pinia 而不是「消息中心」页面里：
 *   未读角标要同时出现在**导航栏用户下拉菜单**（挂在 App.vue 上，任何时候都存在）和个人中心，
 *   而消息中心页只有用户点进去才存在。轮询逻辑一旦写在页面里，就会退化成
 *   「不进消息中心，角标永远不更新」——这恰恰是待办提醒最需要它更新的场景。
 *
 * 所以：轮询由 App.vue 在应用挂载时启动，卸载时清理；页面只负责读和局部更新。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUnreadCount } from '@/api/notification'
import { useUserStore } from '@/stores/user'

/** 轮询间隔：后端 NotificationController 注释里建议前端 30 秒轮询 */
export const UNREAD_POLL_INTERVAL = 30 * 1000

export const useNotificationStore = defineStore('notification', () => {
  /** 未读条数（后端 count 是 Long→String，这里统一转成数字） */
  const unreadCount = ref(0)
  /** 是否正在轮询（便于在页面上直观调试"有没有在轮"） */
  const polling = ref(false)

  let timer = null

  /**
   * 拉一次未读数
   * 失败**静默**：这是后台轮询，不是用户主动操作，不该弹 toast 打扰。
   */
  async function refresh() {
    const userStore = useUserStore()
    if (!userStore.isLoggedIn) {
      unreadCount.value = 0
      return 0
    }
    try {
      const data = await getUnreadCount()
      unreadCount.value = Number(data?.count ?? 0)
    } catch (error) {
      console.warn('[notification] 未读数拉取失败（已静默）：', error?.message)
    }
    return unreadCount.value
  }

  /** 启动轮询（重复调用会先清掉旧的，避免叠加多个定时器） */
  function startPolling(interval = UNREAD_POLL_INTERVAL) {
    stopPolling()
    polling.value = true
    refresh()
    timer = setInterval(refresh, interval)
  }

  /** 停止轮询（App 卸载 / 退出登录时调用） */
  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    polling.value = false
  }

  /**
   * 本地扣减
   * 消息中心「标记已读」成功后立刻调用，让角标即时反馈，不用等下一次轮询。
   */
  function decrement(n = 1) {
    unreadCount.value = Math.max(0, unreadCount.value - Number(n || 0))
  }

  /** 清零（退出登录时调用，避免把上一个账号的未读数留给下一个账号） */
  function clear() {
    unreadCount.value = 0
  }

  return { unreadCount, polling, refresh, startPolling, stopPolling, decrement, clear }
})
