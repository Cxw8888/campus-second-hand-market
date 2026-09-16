<script setup>
/**
 * 管理端外壳（左侧菜单 + 顶栏 + 内容区）
 *
 * 为什么单独做一个 Layout，而不是复用 AppHeader：
 *   学生侧导航栏挂着「发布商品 / 我的订单 / 消息中心」这些**学生功能**，
 *   管理员在后台看到它们只会困惑，而且点进去全是"我的"数据，没有管理语义。
 *   所以 /admin/** 走自己的壳（在 App.vue 里用 meta.admin 把学生导航栏收起来）。
 *
 * 登录态是**复用**的（不做第二个登录页）：能不能进这个壳由路由守卫按 role===1 决定，
 * 后端侧的真正把关是 AuthInterceptor 的 @RequireRole(1)。
 *
 * 菜单里的「用户管理 / 订单管理 / 分类管理 / 审计日志」本批（5.1）还没实现，
 * 故意做成**禁用 + tooltip 说明**，而不是先放一个点了报错的假按钮 ——
 * 这条规矩在批次 2 就定下来了（后端不支持的功能一律禁用 + tooltip）。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  CollectionTag,
  Goods,
  List,
  SwitchButton,
  Tickets,
  User
} from '@element-plus/icons-vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const username = computed(() => userStore.displayName)
const avatarLabel = computed(() => (username.value || '管').trim().charAt(0))

/** 后续批次要实现的菜单（5.2 / 5.3），先占位并说明原因 */
const pendingMenus = [
  { key: 'user', label: '用户管理', icon: User, note: '将在 5.2 实现' },
  { key: 'order', label: '订单管理', icon: List, note: '将在 5.2 实现' },
  { key: 'category', label: '分类管理', icon: CollectionTag, note: '将在 5.3 实现' },
  { key: 'audit-log', label: '审计日志', icon: Tickets, note: '将在 5.3 实现' }
]

const pageTitle = computed(() => route.meta?.title || '管理后台')

function goBackToSite() {
  router.push({ name: 'home' })
}

async function handleLogout() {
  await userStore.logout()
  ElMessage.success('已退出登录')
  // 管理端页面全部要求登录态，退出后必须离开这里，否则会被守卫再弹回来
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="admin">
    <!-- ---------------- 左：菜单 ---------------- -->
    <aside class="admin__side">
      <div class="admin__brand">
        <BrandLogo :size="30" @click="goBackToSite" />
        <span class="admin__brand-tag">管理后台</span>
      </div>

      <nav class="admin__nav">
        <p class="admin__nav-group">内容管理</p>

        <router-link :to="{ name: 'admin-product-audit' }" class="admin__nav-item">
          <el-icon :size="15"><Goods /></el-icon>
          <span>商品审核</span>
        </router-link>

        <el-tooltip
          v-for="item in pendingMenus"
          :key="item.key"
          :content="item.note"
          placement="right"
        >
          <span class="admin__nav-item is-disabled" aria-disabled="true">
            <el-icon :size="15"><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </span>
        </el-tooltip>
      </nav>

      <div class="admin__side-foot">
        <button type="button" class="admin__back" @click="goBackToSite">
          <el-icon :size="14"><ArrowLeft /></el-icon>
          返回前台
        </button>
      </div>
    </aside>

    <!-- ---------------- 右：顶栏 + 内容 ---------------- -->
    <div class="admin__main">
      <header class="admin__topbar">
        <div class="admin__topbar-left">
          <h1 class="admin__title">{{ pageTitle }}</h1>
          <span class="admin__subtitle">管理员操作会写入审计日志</span>
        </div>

        <div class="admin__topbar-right">
          <span class="admin__user">
            <span class="admin__avatar">{{ avatarLabel }}</span>
            <span class="admin__user-meta">
              <b>{{ username }}</b>
              <em>管理员</em>
            </span>
          </span>

          <el-button
            class="admin__logout"
            :icon="SwitchButton"
            plain
            round
            @click="handleLogout"
          >
            退出登录
          </el-button>
        </div>
      </header>

      <main class="admin__content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.admin {
  display: flex;
  min-height: 100vh;
  background: $cm-bg;

  // ---------------- 侧栏 ----------------
  &__side {
    width: $cm-sidebar-width;
    flex: 0 0 $cm-sidebar-width;
    background: $cm-surface;
    border-right: 1px solid $cm-border;
    display: flex;
    flex-direction: column;
    position: sticky;
    top: 0;
    height: 100vh;
  }

  &__brand {
    height: $cm-header-height;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 16px;
    border-bottom: 1px solid $cm-border-light;
  }

  &__brand-tag {
    font-size: 11px;
    font-weight: 700;
    color: $cm-primary-700;
    background: $cm-primary-50;
    border: 1px solid rgba($cm-primary, 0.28);
    border-radius: $cm-radius-pill;
    padding: 2px 8px;
    white-space: nowrap;
  }

  &__nav {
    flex: 1;
    padding: 14px 10px;
    overflow-y: auto;
  }

  &__nav-group {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 1px;
    color: $cm-text-placeholder;
    padding: 6px 8px;
  }

  &__nav-item {
    display: flex;
    align-items: center;
    gap: 9px;
    height: 40px;
    padding: 0 10px;
    border-radius: $cm-radius;
    font-size: 13.5px;
    font-weight: 600;
    color: $cm-text-secondary;
    text-decoration: none;
    transition:
      background 0.18s ease,
      color 0.18s ease;

    &:hover:not(.is-disabled) {
      background: $cm-hover-bg;
      color: $cm-text;
    }

    // 选中态：与全站品牌绿保持一致（router-link 自带 router-link-active）
    &.router-link-active {
      background: $cm-primary-50;
      color: $cm-primary-700;
    }

    &.is-disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  }

  &__side-foot {
    padding: 12px 10px;
    border-top: 1px solid $cm-border-light;
  }

  &__back {
    width: 100%;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 600;
    color: $cm-text-secondary;
    background: $cm-gray-tag-50;
    border: none;
    border-radius: $cm-radius;
    cursor: pointer;

    &:hover {
      color: $cm-primary-700;
      background: $cm-primary-50;
    }
  }

  // ---------------- 主区 ----------------
  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }

  &__topbar {
    height: $cm-header-height;
    background: $cm-surface;
    border-bottom: 1px solid $cm-border;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 0 22px;
    position: sticky;
    top: 0;
    z-index: 10;
  }

  &__topbar-left {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-width: 0;
  }

  &__title {
    font-size: 17px;
    font-weight: 700;
    color: $cm-text;
  }

  &__subtitle {
    font-size: 12px;
    color: $cm-text-placeholder;

    @include cm-max($cm-bp-sm) {
      display: none;
    }
  }

  &__topbar-right {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  &__user {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: $cm-gradient-brand;
    color: #fff;
    font-size: 14px;
    font-weight: 700;
    @include cm-center;
  }

  &__user-meta {
    display: flex;
    flex-direction: column;
    line-height: 1.25;

    b {
      font-size: 13px;
      color: $cm-text;
    }

    em {
      font-size: 11px;
      font-style: normal;
      color: $cm-primary-700;
    }
  }

  &__content {
    flex: 1;
    padding: 20px 22px 40px;
    min-width: 0;
  }
}
</style>
