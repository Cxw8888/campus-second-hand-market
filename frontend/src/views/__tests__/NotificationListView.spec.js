/**
 * 消息中心：未读标记逻辑 + 跳转目标解析 + 全部已读防连点
 *
 * 校园二手语义：消息中心是**待办入口**，不是纯展示 ——
 *   订单通知要能点进订单详情（bizId=订单ID），审核通知要能点进商品详情（bizId=商品ID）。
 *   同时 bizId=0 必须**不跳转**（后端封禁通知就是 type=1 + bizId=0，
 *   照字面拼 /order/detail/0 会直接开一个错误页）。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

const getNotificationListMock = vi.fn()
const markReadMock = vi.fn()
const markAllReadMock = vi.fn()
const getUnreadCountMock = vi.fn()

vi.mock('@/api/notification', () => ({
  getNotificationList: (...a) => getNotificationListMock(...a),
  markNotificationRead: (...a) => markReadMock(...a),
  markAllNotificationsRead: (...a) => markAllReadMock(...a),
  getUnreadCount: (...a) => getUnreadCountMock(...a)
}))

import NotificationListView from '@/views/NotificationListView.vue'
import { useUserStore } from '@/stores/user'
import { useNotificationStore } from '@/stores/notification'
import { resolveNotificationTarget } from '@/utils/constants'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** 三种类型各一条，外加一条 bizId=0 的"无业务对象"通知 */
const ORDER_NOTICE = {
  id: '101',
  type: 1,
  bizType: 1,
  bizId: '5001',
  content: '您出售的商品「考研数学复习全书」有新订单，请及时处理',
  isRead: 0,
  createTime: '2026-09-16 16:00:00'
}
const AUDIT_NOTICE = {
  id: '102',
  type: 2,
  bizType: 2,
  bizId: '30',
  content: '您发布的商品「线性代数教材」已通过审核',
  isRead: 0,
  createTime: '2026-09-16 15:00:00'
}
const SYSTEM_NOTICE = {
  id: '103',
  type: 3,
  bizType: 0,
  bizId: '0',
  content: '系统维护通知：今晚 23:00-24:00 短暂停机',
  isRead: 0,
  createTime: '2026-09-16 14:00:00'
}
/** type=1 但 bizId=0：后端封禁通知就是这么发的，绝不能跳订单详情 */
const BAN_NOTICE = {
  id: '104',
  type: 1,
  bizType: 1,
  bizId: '0',
  content: '您的账号因违规已被封禁，相关订单已冻结',
  isRead: 1,
  createTime: '2026-09-16 13:00:00'
}

async function mountPage(records = [ORDER_NOTICE, AUDIT_NOTICE, SYSTEM_NOTICE, BAN_NOTICE]) {
  getNotificationListMock.mockResolvedValue({
    total: String(records.length),
    pages: '1',
    current: '1',
    size: '10',
    records
  })
  getUnreadCountMock.mockResolvedValue({ count: '3' })
  markReadMock.mockResolvedValue('ok')
  markAllReadMock.mockResolvedValue({ updated: 3 })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/notification/list', name: 'notification-list', component: NotificationListView },
      { path: '/order/detail/:orderId', name: 'order-detail', component: { template: '<div/>' } },
      { path: '/product/:id', name: 'product-detail', component: { template: '<div/>' } },
      { path: '/', name: 'home', component: { template: '<div/>' } }
    ]
  })
  await router.push('/notification/list')
  await router.isReady()

  const pinia = createPinia()
  setActivePinia(pinia)
  // 需要登录态：未读数只在已登录时才拉取
  useUserStore().token = 'test-token'

  const wrapper = mount(NotificationListView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } }
  })
  await flushPromises()
  return { wrapper, router, store: useNotificationStore() }
}

describe('resolveNotificationTarget：按类型解析跳转目标', () => {
  it('type=1 订单通知 → 订单详情，参数取 bizId', () => {
    expect(resolveNotificationTarget(ORDER_NOTICE)).toEqual({
      name: 'order-detail',
      params: { orderId: '5001' }
    })
  })

  it('type=2 审核通知 → 商品详情，参数取 bizId', () => {
    expect(resolveNotificationTarget(AUDIT_NOTICE)).toEqual({
      name: 'product-detail',
      params: { id: '30' }
    })
  })

  it('type=3 系统通知 → 不跳转', () => {
    expect(resolveNotificationTarget(SYSTEM_NOTICE)).toBeNull()
  })

  it('bizId=0（无关联业务对象）→ 即使 type=1 也不跳转', () => {
    expect(resolveNotificationTarget(BAN_NOTICE)).toBeNull()
  })
})

describe('NotificationListView 未读标记逻辑', () => {
  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('四条消息都渲染，未读加粗、已读不加粗', async () => {
    const { wrapper } = await mountPage()
    const items = wrapper.findAll('.notice-item')
    expect(items).toHaveLength(4)
    expect(items[0].classes()).toContain('is-unread') // 订单通知（未读）
    expect(items[3].classes()).not.toContain('is-unread') // 封禁通知（已读）
  })

  it('点击未读的订单通知：标记已读 + 未读数 -1 + 跳到订单详情', async () => {
    const { wrapper, router, store } = await mountPage()
    store.unreadCount = 3

    const orderItem = wrapper.findAll('.notice-item')[0]
    expect(orderItem.classes()).toContain('is-unread')

    await orderItem.trigger('click')
    await flushPromises()

    // ① 调了标记已读接口（静默）
    expect(markReadMock).toHaveBeenCalledTimes(1)
    expect(markReadMock.mock.calls[0][0]).toBe('101')
    // ② 本地状态更新：条目不再是未读
    expect(orderItem.classes()).not.toContain('is-unread')
    // ③ 全局未读数 -1（导航栏角标同步）
    expect(store.unreadCount).toBe(2)
    // ④ 跳转到订单详情，参数是 bizId
    expect(router.currentRoute.value.name).toBe('order-detail')
    expect(router.currentRoute.value.params.orderId).toBe('5001')
  })

  it('点击未读的审核通知 → 跳到商品详情', async () => {
    const { wrapper, router } = await mountPage()
    await wrapper.findAll('.notice-item')[1].trigger('click')
    await flushPromises()

    expect(markReadMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.params.id).toBe('30')
  })

  it('点击系统通知：标记已读但不跳转', async () => {
    const { wrapper, router } = await mountPage()
    await wrapper.findAll('.notice-item')[2].trigger('click')
    await flushPromises()

    expect(markReadMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('notification-list')
  })

  it('点击已读消息：不再调标记接口', async () => {
    const { wrapper } = await mountPage()
    // 第 4 条（封禁通知）isRead=1，且 bizId=0
    await wrapper.findAll('.notice-item')[3].trigger('click')
    await flushPromises()
    expect(markReadMock).not.toHaveBeenCalled()
  })

  it('「全部标记已读」连点 5 次只发 1 次请求', async () => {
    const { wrapper, store } = await mountPage()
    store.unreadCount = 3
    await flushPromises()

    // ⚠️ 这个 mockImplementation 必须放在 mountPage() 之后：
    //    mountPage 内部会 mockResolvedValue 复位它，写在前面会被覆盖掉，
    //    导致请求立刻 resolve、锁提前释放，测不出防连点。
    let resolveAll
    markAllReadMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveAll = resolve
        })
    )

    const btn = wrapper.findAll('button').find((b) => b.text().includes('全部标记已读'))
    expect(btn).toBeTruthy()

    for (let i = 0; i < 5; i++) {
      await btn.trigger('click')
    }
    await flushPromises()
    expect(markAllReadMock).toHaveBeenCalledTimes(1)

    resolveAll({ updated: 3 })
    await flushPromises()
    await flushPromises()

    // 全部置为已读 + 角标清零
    expect(store.unreadCount).toBe(0)
    expect(wrapper.findAll('.notice-item').every((i) => !i.classes().includes('is-unread'))).toBe(true)
  })
})
