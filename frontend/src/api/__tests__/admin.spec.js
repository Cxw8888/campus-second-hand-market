/**
 * 管理端接口层契约测试（第五批 5.1）
 *
 * 这一层只做一件事：把页面参数翻译成后端真正认识的形状。
 * 而后端有三处形状是「写错了不报错、只是悄悄失效」的，所以必须用测试钉住：
 *
 *   ① reason 是 **query 参数**（@RequestParam），不是 body —— 写成 body 时后端收不到，
 *      审计日志里的原因会变成默认文案，接口却依然返回 200。
 *   ② unfreeze 的 target 必须**大写** CANCEL/COMPLETE（后端是 @Pattern），传小写直接 100。
 *   ③ status 的默认值由**后端**字段决定（3-待审核），所以「不传」和「传空」完全不是一回事，
 *      这里锁住的是：传了就原样传过去，我们绝不会把 status 静默丢掉。
 *
 * 另外还锁住 pruneEmpty 的一个关键行为：**false 不能被当成空值丢掉**
 * （审核不通过就是 pass=false，丢了这个参数后端会因 @NotNull 直接 100）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const getMock = vi.fn()
const postMock = vi.fn()
const putMock = vi.fn()
const deleteMock = vi.fn()

vi.mock('@/utils/request', () => ({
  default: {
    get: (...args) => getMock(...args),
    post: (...args) => postMock(...args),
    put: (...args) => putMock(...args),
    delete: (...args) => deleteMock(...args)
  }
}))

import {
  auditProduct,
  banUser,
  createCategory,
  deleteCategory,
  forceOfflineProduct,
  forceRefundOrder,
  getAdminOrderList,
  getAdminProductList,
  getAdminStatsHotProducts,
  getAdminStatsOrderStatus,
  getAdminStatsOverview,
  getAdminStatsProductCategory,
  getAdminStatsTrend,
  getAdminUserList,
  getAuditLogList,
  migrateCategory,
  unbanUser,
  unfreezeOrder,
  updateCategory
} from '@/api/admin'

/** 取某次调用的 axios config（GET：第二个参数） */
const configOf = (mock, index = 0) => mock.mock.calls[index][1]
/** 取某次调用的 axios config（PUT/POST **带 null body** 的三参数写法：第三个参数） */
const configOf3 = (mock, index = 0) => mock.mock.calls[index][2]

describe('api/admin 接口契约', () => {
  beforeEach(() => {
    getMock.mockReset().mockResolvedValue({})
    postMock.mockReset().mockResolvedValue({})
    putMock.mockReset().mockResolvedValue({})
    deleteMock.mockReset().mockResolvedValue({})
  })

  it('列表默认带 page=1、size=10（后端 PageQuery 默认值），keyword 为空时不下发', async () => {
    // 页面侧已把 keyword trim 过，空串不该被拼进 query（后端会把空串当有效参数）
    await getAdminProductList({ status: 3, keyword: '' })

    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock.mock.calls[0][0]).toBe('/admin/product/audit/list')
    expect(configOf(getMock).params).toEqual({ page: 1, size: 10, status: 3 })
    expect(configOf(getMock).silent).toBe(false)
  })

  it('列表支持 silent（页面自己渲染错误态时用）', async () => {
    await getAdminUserList({ status: 1 }, { silent: true })

    expect(getMock.mock.calls[0][0]).toBe('/admin/user/list')
    expect(configOf(getMock).params).toEqual({ page: 1, size: 10, status: 1 })
    expect(configOf(getMock).silent).toBe(true)
  })

  it('商品审核：pass=false 必须原样传（不能被 pruneEmpty 当空值丢掉），reason 放 body', async () => {
    await auditProduct('30', { pass: false, reason: '图片不清晰' })

    expect(putMock.mock.calls[0][0]).toBe('/admin/product/audit/30')
    expect(putMock.mock.calls[0][1]).toEqual({ pass: false, reason: '图片不清晰' })
  })

  it('商品审核：通过且不填原因时只传 pass=true', async () => {
    await auditProduct('30', { pass: true })

    expect(putMock.mock.calls[0][1]).toEqual({ pass: true })
  })

  it('强制下架：reason 走 **query**，body 必须是 null（写成 body 后端拿不到）', async () => {
    await forceOfflineProduct('30', '涉嫌违规内容')

    expect(putMock.mock.calls[0][0]).toBe('/admin/product/force-offline/30')
    expect(putMock.mock.calls[0][1]).toBeNull()
    expect(configOf3(putMock).params).toEqual({ reason: '涉嫌违规内容' })
  })

  it('封禁 / 解封：无 body、无 query', async () => {
    await banUser('26')
    await unbanUser('26')

    expect(putMock.mock.calls[0][0]).toBe('/admin/user/ban/26')
    expect(putMock.mock.calls[0][1]).toBeUndefined()
    expect(putMock.mock.calls[1][0]).toBe('/admin/user/unban/26')
  })

  it('订单解冻：target 走 body 且保持大写', async () => {
    await unfreezeOrder('68', 'CANCEL')
    await unfreezeOrder('68', 'COMPLETE')

    expect(putMock.mock.calls[0][0]).toBe('/admin/order/unfreeze/68')
    expect(putMock.mock.calls[0][1]).toEqual({ target: 'CANCEL' })
    expect(putMock.mock.calls[1][1]).toEqual({ target: 'COMPLETE' })
  })

  it('强制退款：reason 走 **query**，body 必须是 null', async () => {
    await forceRefundOrder('68', '买家投诉')

    expect(putMock.mock.calls[0][0]).toBe('/admin/order/force-refund/68')
    expect(putMock.mock.calls[0][1]).toBeNull()
    expect(configOf3(putMock).params).toEqual({ reason: '买家投诉' })
  })

  it('管理端订单列表：orderNo 原样传（后端是精确匹配）', async () => {
    await getAdminOrderList({ orderNo: '358478719859429376', page: 2 })

    expect(getMock.mock.calls[0][0]).toBe('/admin/order/list')
    expect(configOf(getMock).params).toEqual({
      page: 2,
      size: 10,
      orderNo: '358478719859429376'
    })
  })

  it('审计日志：operationType 与时间字符串原样传（格式由后端 @DateTimeFormat 决定）', async () => {
    await getAuditLogList({
      operationType: 'BAN_USER',
      startTime: '2026-09-01 00:00:00',
      endTime: '2026-09-30 23:59:59'
    })

    expect(getMock.mock.calls[0][0]).toBe('/admin/audit-log/list')
    expect(configOf(getMock).params).toMatchObject({
      operationType: 'BAN_USER',
      startTime: '2026-09-01 00:00:00',
      endTime: '2026-09-30 23:59:59'
    })
  })

  it('分类 CRUD 与迁移：路径与 body 形状与后端一致', async () => {
    await createCategory({ name: '乐器', sort: 0 })
    await updateCategory('9', { name: '乐器配件', sort: 2 })
    await deleteCategory('9')
    await migrateCategory('9', '2')

    expect(postMock.mock.calls[0][0]).toBe('/admin/category')
    expect(postMock.mock.calls[0][1]).toEqual({ name: '乐器', sort: 0 })

    expect(putMock.mock.calls[0][0]).toBe('/admin/category/9')
    expect(putMock.mock.calls[0][1]).toEqual({ name: '乐器配件', sort: 2 })

    expect(deleteMock.mock.calls[0][0]).toBe('/admin/category/9')

    expect(putMock.mock.calls[1][0]).toBe('/admin/category/migrate')
    expect(putMock.mock.calls[1][1]).toEqual({ fromCategoryId: '9', toCategoryId: '2' })
  })

  it('统计三接口（5.5.1）：路径正确、无 query 参数、默认 silent=false（页面自己传 true）', async () => {
    await getAdminStatsOverview()
    await getAdminStatsOrderStatus()
    await getAdminStatsProductCategory()

    expect(getMock.mock.calls.map((c) => c[0])).toEqual([
      '/admin/stats/overview',
      '/admin/stats/order-status',
      '/admin/stats/product-category'
    ])
    // 三个接口都不接受任何参数：多传 query 只会让后端莫名其妙
    expect(configOf(getMock, 0).params ?? {}).toEqual({})
    expect(configOf(getMock, 1).params ?? {}).toEqual({})
    expect(configOf(getMock, 2).params ?? {}).toEqual({})
    expect(configOf(getMock, 0).silent).toBe(false)
  })

  it('统计三接口：silent=true 时原样透传（页面自己渲染错误态，不要拦截器弹 toast）', async () => {
    await getAdminStatsOverview({ silent: true })

    expect(configOf(getMock, 0).silent).toBe(true)
  })

  // ---------------- 5.5.2 趋势 / 热门榜 ----------------

  it('统计 · 趋势（5.5.2）：参数只带 days，字符串计数在 **api 层**归一化成 number，日期原样透传', async () => {
    getMock.mockResolvedValue({
      days: '7',
      dates: ['2026-09-12', '2026-09-13'],
      orderCounts: ['0', '36'],
      productCounts: ['3', '2'],
      userCounts: ['0', '5']
    })

    const data = await getAdminStatsTrend(7)

    expect(getMock.mock.calls[0][0]).toBe('/admin/stats/trend')
    expect(configOf(getMock).params).toEqual({ days: 7 })
    expect(data.days).toBe(7)
    // 日期是后端序列化好的字符串：原样透传，**不做任何格式化**
    expect(data.dates).toEqual(['2026-09-12', '2026-09-13'])
    // 计数是 "0"/"36" 这种字符串（Long → String），api 层已经转成 number
    expect(data.orderCounts).toEqual([0, 36])
    expect(data.productCounts).toEqual([3, 2])
    expect(data.userCounts).toEqual([0, 5])
    expect(typeof data.orderCounts[1]).toBe('number')
  })

  it('统计 · 趋势：后端给残缺数据（无 dates / 无计数）时返回空数组与 0，绝不返回 null', async () => {
    getMock.mockResolvedValue({})

    const data = await getAdminStatsTrend(30)

    expect(configOf(getMock).params).toEqual({ days: 30 })
    expect(data).toEqual({ days: 30, dates: [], orderCounts: [], productCounts: [], userCounts: [] })
  })

  it('统计 · 热门榜（5.5.2）：productId 保持字符串（禁止 Number(id)），orderCount 转 number，null 分类变空串', async () => {
    getMock.mockResolvedValue({
      days: '7',
      items: [
        { productId: '14', productTitle: '二手高等数学教材（第三版）', categoryName: '教材书籍', orderCount: '5' },
        // 商品已删除：categoryName 被 Jackson 的 non_null 策略整个省略
        { productId: '21', productTitle: '英语四级真题册', orderCount: '2' }
      ]
    })

    const data = await getAdminStatsHotProducts(7, 10)

    expect(getMock.mock.calls[0][0]).toBe('/admin/stats/hot-products')
    expect(configOf(getMock).params).toEqual({ days: 7, limit: 10 })
    expect(data.days).toBe(7)
    expect(data.items[0]).toEqual({
      productId: '14',
      productTitle: '二手高等数学教材（第三版）',
      categoryName: '教材书籍',
      orderCount: 5
    })
    // id 全程字符串（雪花 Long 超 2^53，Number() 会丢精度）
    expect(typeof data.items[0].productId).toBe('string')
    expect(typeof data.items[0].orderCount).toBe('number')
    // 缺失的分类名归一化成空串（页面显示「—」），不是 undefined
    expect(data.items[1].categoryName).toBe('')
  })

  it('统计 · 热门榜：无数据（items 缺失）时返回空数组，days 用入参兜底', async () => {
    getMock.mockResolvedValue({ days: '7' })

    const data = await getAdminStatsHotProducts(7, 10)

    expect(data.items).toEqual([])
    expect(data.days).toBe(7)
  })
})
