<script setup>
/**
 * 应用外壳
 *
 * 职责三件：
 *   1. 决定哪些页面显示顶部导航栏（登录/注册/404 是独立整屏布局，不显示）
 *   2. 提供路由切换的淡入过渡
 *   3. 启动/停止「站内信未读数」的全局轮询（详见下方注释）
 */
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import { useUserStore } from '@/stores/user'
import { useNotificationStore } from '@/stores/notification'

const route = useRoute()
const userStore = useUserStore()
const notificationStore = useNotificationStore()

/** 这三种页面是「整屏独立布局」，不套导航栏 */
const STANDALONE_ROUTES = ['login', 'register', 'not-found']

/**
 * 管理端（meta.admin）也要收起学生侧导航栏：管理端有自己的 AdminLayout 外壳，
 * 上面挂着「发布商品 / 我的订单」这类学生功能只会让人困惑。
 */
const showHeader = computed(() => !STANDALONE_ROUTES.includes(route.name) && !route.meta?.admin)

/**
 * 未读数轮询放在这里（而不是消息中心页）的原因：
 *   导航栏用户下拉菜单的未读角标在任何页面都要可见，
 *   如果轮询写在消息中心页里，用户不点进去角标就永远不动 —— 那就失去了提醒的意义。
 *
 * 生命周期：挂载启动 → 卸载清理；登录状态变化时启停（退出登录要停止并把角标清零，
 * 否则会把上一个账号的未读数留给下一个账号）。
 */
onMounted(() => {
  if (userStore.isLoggedIn) notificationStore.startPolling()
})

onBeforeUnmount(() => {
  notificationStore.stopPolling()
})

watch(
  () => userStore.isLoggedIn,
  (loggedIn) => {
    if (loggedIn) {
      notificationStore.startPolling()
    } else {
      notificationStore.stopPolling()
      notificationStore.clear()
    }
  }
)
</script>

<template>
  <div class="cm-app">
    <AppHeader v-if="showHeader" />

    <router-view v-slot="{ Component }">
      <transition name="cm-fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<style scoped lang="scss">
.cm-app {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}
</style>
