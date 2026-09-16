<script setup>
/**
 * 顶部导航栏
 *
 * 结构：Logo ｜ 搜索框 ｜ 发布按钮 + 用户区
 * 搜索与首页的联动方式：header 只负责把关键字写进路由 query（/?keyword=xxx），
 * 由 HomeView 监听 query 去请求接口。这样搜索状态可以直接分享/刷新而不丢失，
 * 也避免两个组件互相传事件。
 *
 * 未读站内信角标：读全局 store（轮询在 App.vue 里启动），这样任何页面都能看到待办提醒。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Plus, ArrowDown, User, List, SwitchButton, Bell, Goods } from '@element-plus/icons-vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { useUserStore } from '@/stores/user'
import { useNotificationStore } from '@/stores/notification'
import { avatarText } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const notificationStore = useNotificationStore()

/** 未读数（>99 显示 99+） */
const unreadCount = computed(() => notificationStore.unreadCount)
const unreadBadge = computed(() => (unreadCount.value > 99 ? '99+' : String(unreadCount.value)))

const keyword = ref(String(route.query.keyword || ''))

/** 路由上的关键字变了（比如点了分类/重置），输入框要跟着回填 */
watch(
  () => route.query.keyword,
  (value) => {
    keyword.value = String(value || '')
  }
)

const avatarLabel = computed(() => avatarText(userStore.userInfo))

function goHome() {
  router.push({ name: 'home' })
}

/** 提交搜索：只改 query，真正的请求交给 HomeView */
function submitSearch() {
  const value = keyword.value.trim()
  router.push({
    name: 'home',
    query: value ? { keyword: value } : {}
  })
}

function goPublish() {
  router.push({ name: 'product-publish' })
}

function goLogin() {
  router.push({ name: 'login' })
}

function goRegister() {
  router.push({ name: 'register' })
}

/** 下拉菜单统一入口，避免模板里塞一长串 if */
async function handleCommand(command) {
  if (command === 'logout') {
    await userStore.logout()
    ElMessage.success('已退出登录')
    // 当前页需要登录态的话，退到登录页；否则留在原地（首页游客也能看）
    if (route.meta?.requiresAuth) router.push({ name: 'login' })
    return
  }
  if (command === 'profile') {
    router.push({ name: 'user-profile' })
    return
  }
  if (command === 'orders') {
    router.push({ name: 'order-list' })
    return
  }
  if (command === 'notifications') {
    router.push({ name: 'notification-list' })
    return
  }
  if (command === 'products') {
    router.push({ name: 'product-my' })
  }
}
</script>

<template>
  <header class="app-header">
    <div class="cm-container app-header__inner">
      <!-- 左：品牌 -->
      <BrandLogo :size="34" @click="goHome" />

      <!-- 中：搜索 -->
      <div class="app-header__search">
        <el-input
          v-model="keyword"
          placeholder="搜索教材、数码、生活用品…"
          clearable
          :prefix-icon="Search"
          @keyup.enter="submitSearch"
          @clear="submitSearch"
        />
        <el-button type="primary" class="app-header__search-btn" @click="submitSearch">搜索</el-button>
      </div>

      <!-- 右：发布 + 用户 -->
      <div class="app-header__actions">
        <el-button type="primary" round :icon="Plus" class="app-header__publish" @click="goPublish">
          发布商品
        </el-button>

        <template v-if="userStore.isLoggedIn">
          <el-dropdown trigger="click" @command="handleCommand">
            <span class="app-header__user">
              <!-- 有未读时头像右上角挂一个小红点：不展开菜单也能看到待办提醒 -->
              <span class="app-header__avatar-wrap">
                <span class="app-header__avatar">{{ avatarLabel }}</span>
                <span v-if="unreadCount > 0" class="app-header__avatar-dot" aria-hidden="true" />
              </span>
              <span class="app-header__nickname">{{ userStore.displayName }}</span>
              <el-icon class="app-header__caret" :size="12"><ArrowDown /></el-icon>
            </span>

            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile" :icon="User">个人中心</el-dropdown-item>
                <el-dropdown-item command="notifications" :icon="Bell">
                  <span class="app-header__menu-item">
                    消息中心
                    <span v-if="unreadCount > 0" class="app-header__menu-badge cm-num">{{ unreadBadge }}</span>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item command="orders" :icon="List">我的订单</el-dropdown-item>
                <!-- 学生既买也卖，卖家侧入口和订单入口同等重要 -->
                <el-dropdown-item command="products" :icon="Goods">我的发布</el-dropdown-item>
                <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>

        <template v-else>
          <el-button link class="app-header__login" @click="goLogin">登录</el-button>
          <el-button round plain type="primary" @click="goRegister">注册</el-button>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: $cm-header-height;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid $cm-border-light;
  // 顶部一条极细的品牌绿线，作为全站视觉线索
  box-shadow: 0 1px 0 rgba(16, 185, 129, 0.16);

  &__inner {
    height: 100%;
    display: flex;
    align-items: center;
    gap: 24px;
  }

  &__search {
    flex: 1;
    max-width: 460px;
    display: flex;
    gap: 8px;

    :deep(.el-input__wrapper) {
      border-radius: $cm-radius-pill;
      background: $cm-bg;
      box-shadow: none;
      border: 1px solid $cm-border;

      &.is-focus {
        background: #ffffff;
        border-color: $cm-primary-light;
        box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.12);
      }
    }
  }

  &__search-btn {
    border-radius: $cm-radius-pill;
    padding: 0 18px;
  }

  &__actions {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__publish {
    font-weight: 600;
    box-shadow: 0 2px 10px rgba(16, 185, 129, 0.28);
  }

  &__user {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 4px 10px 4px 4px;
    border-radius: $cm-radius-pill;
    cursor: pointer;
    transition: background 0.18s ease;

    &:hover {
      background: $cm-hover-bg;
    }
  }

  &__avatar {
    @include cm-center;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    font-size: 13px;
    font-weight: 600;
    color: #ffffff;
    background: $cm-gradient-brand;
    flex: none;
  }

  // 头像 + 未读红点：用相对定位把红点挂在头像右上角
  &__avatar-wrap {
    position: relative;
    display: inline-flex;
    flex: none;
  }

  &__avatar-dot {
    position: absolute;
    top: -1px;
    right: -1px;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: $cm-danger;
    // 描一圈白边，避免和渐变头像糊在一起
    box-shadow: 0 0 0 2px #ffffff;
  }

  // 下拉菜单里的「消息中心 + 未读角标」
  &__menu-item {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }

  &__menu-badge {
    min-width: 18px;
    height: 18px;
    padding: 0 6px;
    border-radius: $cm-radius-pill;
    background: $cm-danger;
    color: #ffffff;
    font-size: 11px;
    font-weight: 700;
    line-height: 18px;
    text-align: center;
  }

  &__nickname {
    font-size: 14px;
    color: $cm-text;
    max-width: 120px;
    @include cm-ellipsis;
  }

  &__caret {
    color: $cm-text-secondary;
  }

  &__login {
    color: $cm-text-secondary;

    &:hover {
      color: $cm-primary;
    }
  }

  @include cm-max($cm-bp-sm) {
    &__inner {
      gap: 12px;
    }

    &__search {
      max-width: none;
    }

    &__nickname,
    &__publish :deep(span) {
      display: none;
    }
  }
}
</style>
