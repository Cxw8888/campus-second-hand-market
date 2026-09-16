/**
 * 路由表 + 全局前置守卫
 *
 * 命名路由（name）在代码里比裸路径更安全：改路径时只改这里一处。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/auth'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '首页' }
  },
  {
    // 登录 / 注册共用同一个页面组件，用 props.mode 决定默认激活哪个 Tab。
    // 做成两个路由而不是一个带 query 的路由，是为了让 /register 能直接被分享、被收藏。
    path: '/login',
    name: 'login',
    component: () => import('@/views/AuthView.vue'),
    props: { mode: 'login' },
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/AuthView.vue'),
    props: { mode: 'register' },
    meta: { title: '注册' }
  },
  {
    path: '/product/publish',
    name: 'product-publish',
    component: () => import('@/views/ProductPublishView.vue'),
    meta: { title: '发布商品', requiresAuth: true }
  },
  {
    path: '/product/publish-success/:productId',
    name: 'product-publish-success',
    component: () => import('@/views/ProductPublishSuccessView.vue'),
    props: true,
    meta: { title: '发布成功', requiresAuth: true }
  },
  {
    path: '/product/my',
    name: 'product-my',
    component: () => import('@/views/ProductMyView.vue'),
    meta: { title: '我的商品', requiresAuth: true }
  },
  {
    path: '/product/edit/:id',
    name: 'product-edit',
    component: () => import('@/views/ProductEditView.vue'),
    props: true,
    meta: { title: '编辑商品', requiresAuth: true }
  },
  {
    path: '/product/:id',
    name: 'product-detail',
    component: () => import('@/views/ProductDetailView.vue'),
    props: true,
    meta: { title: '商品详情' }
  },
  {
    path: '/order/create',
    name: 'order-create',
    component: () => import('@/views/OrderCreateView.vue'),
    meta: { title: '确认订单', requiresAuth: true }
  },
  {
    // 下单成功页：orderId 是雪花 Long 序列化后的字符串，整体当作字符串用
    path: '/order/success/:orderId',
    name: 'order-success',
    component: () => import('@/views/OrderSuccessView.vue'),
    props: true,
    meta: { title: '下单成功', requiresAuth: true }
  },
  {
    path: '/order/list',
    name: 'order-list',
    component: () => import('@/views/OrderListView.vue'),
    meta: { title: '我的订单', requiresAuth: true }
  },
  {
    path: '/order/detail/:orderId',
    name: 'order-detail',
    component: () => import('@/views/OrderDetailView.vue'),
    props: true,
    meta: { title: '订单详情', requiresAuth: true }
  },
  {
    path: '/user/profile',
    name: 'user-profile',
    component: () => import('@/views/UserProfileView.vue'),
    meta: { title: '个人中心', requiresAuth: true }
  },
  {
    path: '/favorite/list',
    name: 'favorite-list',
    component: () => import('@/views/FavoriteListView.vue'),
    meta: { title: '我的收藏', requiresAuth: true }
  },
  {
    path: '/notification/list',
    name: 'notification-list',
    component: () => import('@/views/NotificationListView.vue'),
    meta: { title: '消息中心', requiresAuth: true }
  },
  {
    // ---------------- 管理端（第五批）----------------
    // 复用**同一套登录态**：不再做第二个登录页，只靠 role 区分。
    // meta.admin 让 App.vue 收起「面向学生」的顶部导航，改由 AdminLayout 提供管理端自己的壳。
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    // requiresAdmin 是父级 meta，vue-router 会把 matched 上的 meta 合并进 to.meta，子路由自动继承
    meta: { requiresAuth: true, requiresAdmin: true, admin: true },
    children: [
      {
        path: '',
        // 访问 /admin 直接进主要待办：商品审核
        redirect: { name: 'admin-product-audit' }
      },
      {
        path: 'product/audit',
        name: 'admin-product-audit',
        component: () => import('@/views/admin/AdminProductAuditView.vue'),
        meta: { title: '商品审核' }
      }
    ]
  },
  {
    // 403：编辑别人商品时跳这里（与 404 区分开，让「没权限」这件事对用户可见）
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无权限访问' }
  },
  {
    // 404 兜底：必须放在最后
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 切换路由回到顶部（详情页很长时体验明显更好）
  scrollBehavior(to, from, savedPosition) {
    return savedPosition || { top: 0 }
  }
})

// ------------------------------------------------------------------ 全局前置守卫
router.beforeEach(async (to) => {
  // ① 未登录访问需要登录的页面 → 跳登录页，并把原目标塞进 redirect，登录后可以跳回来
  if (to.meta?.requiresAuth && !getToken()) {
    return {
      name: 'login',
      query: { redirect: to.fullPath }
    }
  }

  // ② 管理端：登录了还不够，必须是 role=1
  //    后端侧真正的把关在 AuthInterceptor 的 @RequireRole(1)（非管理员 → code=403），
  //    这里只是别让普通用户先看到一个空壳页面再被接口打回来。
  if (to.meta?.requiresAdmin) {
    const userStore = useUserStore()

    // 兜底确认身份：token 在、本地却缺 role（缓存被清过/旧版本登录过）时补一次资料，
    // 否则真管理员会被自己的前端当成普通用户拦在门外。
    if (!userStore.hasRole) await userStore.ensureProfile()

    // ensureProfile 若命中 401，request.js 的拦截器已经清掉 token 并跳登录页了，
    // 这里再判一次，保证「token 刚失效」这一拍也能落到登录页而不是 403 页。
    if (!getToken()) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    if (!userStore.isAdmin) {
      return { name: 'forbidden', query: { from: 'admin' } }
    }
  }

  return true
})

// 标题跟着路由走，方便论文截图/演示时一眼看出当前页面
router.afterEach((to) => {
  const base = '校园二手交易'
  document.title = to.meta?.title ? `${to.meta.title} · ${base}` : base
})

export default router
