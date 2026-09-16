/**
 * 订单相关接口（对应后端 OrderController，前缀 /api/v1/order）
 *
 * 全部为强制认证路径：未登录会返回 HTTP 401，由 request.js 统一跳登录页。
 *
 * ⚠️ 三条贯穿全文件的约定：
 *   1. orderId / orderNo / userId / sellerId / productId 都是后端序列化的**字符串**
 *      （雪花算法 Long 直接转 number 会精度丢失），因此任何地方都不得 parseInt / Number()。
 *   2. 金额字段（amount / productPrice）是 BigDecimal，序列化成数字，展示统一用 formatPrice()。
 *   3. 业务失败仍返回 HTTP 200，靠 body.code 区分；拦截器已负责弹提示与 reject。
 */
import request from '@/utils/request'
import { pruneEmpty } from '@/utils/format'
import { ORDER_PAGE_SIZE } from '@/utils/constants'

/**
 * 获取下单防重 Token（Redis order:token:{userId}:{uuid}，TTL 5 分钟）
 *
 * 下单前必须先拿它，提交时通过请求头 X-Order-Token 回传；
 * 后端用 Lua 原子「校验并删除」，保证同一个 Token 只能成功一次。
 *
 * @returns {Promise<{ token: string, expireSeconds: number }>}
 */
export function getOrderToken() {
  return request.get('/order/token')
}

/**
 * 创建订单
 *
 * 后端会：Lua 校验并消费 Token → 事务内读商品最新价 → CAS 扣库存 → 落订单快照。
 * amount 与 tradeType 由后端计算/快照，前端**严禁**传（传了也会被忽略或校验失败）。
 *
 * @param {object} payload
 * @param {string|number} payload.productId 商品 id（保持字符串原样）
 * @param {number} payload.quantity         购买数量
 * @param {string} [payload.address]        收货地址（邮寄必填）；面交时用于存约定地点
 * @param {string} orderToken               防重 Token（放请求头，优先级高于请求体）
 * @returns {Promise<{ orderId: string, orderNo: string, amount: number, status: number }>}
 */
export function createOrder(payload, orderToken, { silent = false } = {}) {
  const { productId, quantity, address } = payload
  return request.post(
    '/order',
    pruneEmpty({ productId, quantity, address }),
    {
      headers: orderToken ? { 'X-Order-Token': orderToken } : {},
      // 下单页要对 201/202/203 给出更贴合场景的提示，所以这里静默，由页面自己弹
      silent
    }
  )
}

/**
 * 订单列表
 * @param {'buyer'|'seller'} role buyer=我买到的（默认），seller=我卖出的
 * @param {object} [params] status 可选状态筛选、page、size
 * @returns {Promise<{ total, pages, current, size, records: Array }>}
 */
export function getOrderList(role = 'buyer', params = {}) {
  const { silent, ...rest } = params
  return request.get('/order/list', {
    params: pruneEmpty({ role, page: 1, size: ORDER_PAGE_SIZE, ...rest }),
    silent
  })
}

/**
 * 订单详情（归属校验失败返回 203；商品已删除时后端回看快照）
 * @param {string} orderId
 */
export function getOrderDetail(orderId, { silent = false } = {}) {
  return request.get(`/order/detail/${orderId}`, { silent })
}

/**
 * 支付订单（0→1，仅买家）
 *
 * 这是「模拟支付」：真实资金在线下流转，这里只推进状态机。
 * @param {string} orderId
 */
export function payOrder(orderId) {
  return request.put(`/order/pay/${orderId}`)
}

/**
 * 买家取消订单（0→4，同步库存回补）
 * @param {string} orderId
 * @param {string} [reason] 取消原因，可不填（后端默认「买家主动取消」）
 */
export function cancelOrder(orderId, reason) {
  return request.put(`/order/cancel/${orderId}`, pruneEmpty({ reason }))
}

/**
 * 卖家发货（1→2，仅 trade_type IN (2,3)，面交订单会被后端 209 拦住）
 * @param {string} orderId
 */
export function shipOrder(orderId) {
  return request.put(`/order/ship/${orderId}`)
}

/**
 * 买家确认收货（邮寄 2→3；面交 1→3）
 * @param {string} orderId
 */
export function receiveOrder(orderId) {
  return request.put(`/order/receive/${orderId}`)
}

/**
 * 面交直接完成（0→3，仅卖家；后端用 seller_id 校验）
 * @param {string} orderId
 */
export function finishFaceOrder(orderId) {
  return request.put(`/order/finish-face/${orderId}`)
}

/**
 * 买家申请退款（1/2→6）
 * @param {string} orderId
 * @param {string} reason 退款原因（必填，最长 200）
 */
export function applyRefund(orderId, reason) {
  return request.post(`/order/refund/apply/${orderId}`, { reason })
}

/**
 * 卖家同意退款（6→4，同步库存回补）
 * @param {string} orderId
 */
export function agreeRefund(orderId) {
  return request.put(`/order/refund/agree/${orderId}`)
}

/**
 * 卖家拒绝退款（6→7，进入 3 天申诉期）
 * @param {string} orderId
 * @param {string} rejectReason 拒绝原因（必填，最长 200）
 */
export function rejectRefund(orderId, rejectReason) {
  return request.put(`/order/refund/reject/${orderId}`, { rejectReason })
}
