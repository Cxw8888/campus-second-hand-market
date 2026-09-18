/**
 * 管理端接口（对应后端 AdminController + AdminCategoryController）
 *
 * 前缀：/api/v1/admin/**，**全部要求 role=1**（两个 Controller 都是类级 @RequireRole(1)，
 * 见 AdminController.java:37-38、AdminCategoryController.java:34-35）。
 *
 * 权限语义（AuthInterceptor.java:80-81 / 239-260 + GlobalExceptionHandler.java:48-56）：
 *   · 未带 Token        → HTTP 401 + code=401（NOT_LOGIN「请先登录」）
 *   · Token 失效/被封禁 → HTTP 401 + code=401（UNAUTHORIZED「登录已失效，请重新登录」）
 *   · 登录了但不是管理员 → **HTTP 200** + code=403（FORBIDDEN「无权限访问」）
 *   · 订单不存在/无权处理 → HTTP 200 + code=203
 *   · 商品不存在         → HTTP 200 + code=204
 *   · 状态不允许该操作   → HTTP 200 + code=209
 *
 * ⚠️ 两个容易被写错的地方（已读源码核实，不是猜的）：
 *   1. reason 是 **query 参数**（@RequestParam），不是 body：
 *      AdminController.java:61-62（force-offline）、107-108（force-refund）。
 *      写成 body 后端收不到 → 审计日志里的原因会变成默认文案。
 *   2. unfreeze 的 target 是 **body**，且枚举值必须**大写** CANCEL / COMPLETE：
 *      OrderUnfreezeRequest.java:22-25 是 @Pattern(regexp="CANCEL|COMPLETE")，
 *      传小写 'cancel' 直接 code=100（api-tests.http:2092-2105 就是专门测这个的）。
 */
import request from '@/utils/request'
import { pruneEmpty } from '@/utils/format'
import { ADMIN_PAGE_SIZE } from '@/utils/constants'

// ================================================================ 商品

/**
 * 商品列表（审核用）
 *
 * ⚠️ `status` 的后端默认值是 3（待审核）：**省略参数不等于「全部」**，
 *    要指定状态必须显式传值（AdminProductQuery.java:19、AdminServiceImpl.java:95）。
 *
 * @param {{ status?: number, keyword?: string, userId?: string|number, page?: number, size?: number }} params
 * @param {{ silent?: boolean }} [options] silent=true 时不弹错误提示（页面自己渲染错误态时用）
 * @returns {Promise<{total, pages, current, size, records: Array}>} 分页 ProductListVO（**createTime 升序**，最早的先审）
 */
export function getAdminProductList(params = {}, { silent = false } = {}) {
  return request.get('/admin/product/audit/list', {
    params: pruneEmpty({ page: 1, size: ADMIN_PAGE_SIZE, ...params }),
    silent
  })
}

/**
 * 商品审核：通过 3→1，不通过 3→0 并站内信通知卖家
 *
 * 后端失败语义：商品不存在 → 204；当前状态不是 3（已被别处审过）→ 209。
 *
 * @param {string|number} id 商品ID（雪花 Long，序列化后是字符串，原样回传）
 * @param {{ pass: boolean, reason?: string }} payload pass 是 Boolean 且 @NotNull，false 必须显式传
 */
export function auditProduct(id, { pass, reason = '' } = {}) {
  return request.put(`/admin/product/audit/${id}`, pruneEmpty({ pass, reason }))
}

/**
 * 强制下架商品（任意状态 → 0，不校验当前状态）
 *
 * ⚠️ reason 走 **query 参数**。
 *
 * @param {string|number} id
 * @param {string} [reason]
 */
export function forceOfflineProduct(id, reason = '') {
  return request.put(`/admin/product/force-offline/${id}`, null, {
    params: pruneEmpty({ reason })
  })
}

// ================================================================ 用户

/**
 * 用户列表
 * @param {{ keyword?: string, status?: number, page?: number, size?: number }} params
 *   keyword 同时模糊匹配 学号 / 昵称 / 邮箱
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<{total, pages, current, size, records: Array}>} 分页 AdminUserVO（createTime 降序）
 */
export function getAdminUserList(params = {}, { silent = false } = {}) {
  return request.get('/admin/user/list', {
    params: pruneEmpty({ page: 1, size: ADMIN_PAGE_SIZE, ...params }),
    silent
  })
}

/**
 * 封禁用户（同事务：status=1 + 在售商品下架 + 未完成订单冻结并回补库存 + 审计，提交后 version+1）
 *
 * ⚠️ 幂等语义特殊：用户**已经是封禁态**时后端返回 `code=200 + msg=请勿重复操作`
 *    （AdminServiceImpl.java:171-173 抛的是 ErrorCode.SUCCESS），前端拦截器会当成成功 resolve，
 *    **组件收不到任何错误**。所以按钮必须按列表里的 status 前置禁用，不能靠报错兜。
 *
 * @param {string|number} id
 */
export function banUser(id) {
  return request.put(`/admin/user/ban/${id}`)
}

/** 解封用户（status=0 + version+1；已冻结订单保持 5-待线下处理，**不会**自动恢复原状态） */
export function unbanUser(id) {
  return request.put(`/admin/user/unban/${id}`)
}

// ================================================================ 订单

/**
 * 全量订单查询
 * @param {{ status?: number, orderNo?: string, userId?: string|number, sellerId?: string|number, page?: number, size?: number }} params
 *   ⚠️ orderNo 后端是**精确匹配**（AdminServiceImpl.java:241），不是模糊搜索
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<{total, pages, current, size, records: Array}>} 分页 OrderVO（createTime 降序）
 */
export function getAdminOrderList(params = {}, { silent = false } = {}) {
  return request.get('/admin/order/list', {
    params: pruneEmpty({ page: 1, size: ADMIN_PAGE_SIZE, ...params }),
    silent
  })
}

/**
 * 冻结订单处理
 *
 * 后端失败语义：订单不存在 / 无权处理 → 203；当前状态不是 5 → 209。
 *
 * @param {string|number} id
 * @param {'CANCEL'|'COMPLETE'} target CANCEL=转已取消(4，同步回补库存)；COMPLETE=线下完成(3)。**必须大写**
 */
export function unfreezeOrder(id, target) {
  return request.put(`/admin/order/unfreeze/${id}`, { target })
}

/**
 * 管理员强制退款（6/7→4 + 库存回补 + 审计 + 通知买家）
 *
 * ⚠️ reason 走 **query 参数**；且订单**已经是已取消**时后端返回 `code=200 + msg=请勿重复操作`。
 *
 * @param {string|number} id
 * @param {string} [reason] 不传时后端落「管理员强制退款」
 */
export function forceRefundOrder(id, reason = '') {
  return request.put(`/admin/order/force-refund/${id}`, null, {
    params: pruneEmpty({ reason })
  })
}

// ================================================================ 审计日志

/**
 * 审计日志查询
 * @param {{ operatorId?: string|number, operationType?: string, startTime?: string, endTime?: string, page?: number, size?: number }} params
 *   operationType 取值只能是 AUDIT_OPERATION_TYPE_MAP 里的字符串枚举（精确匹配）；
 *   startTime / endTime 格式固定 `yyyy-MM-dd HH:mm:ss`（AuditLogQuery.java:28-36 @DateTimeFormat）
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<{total, pages, current, size, records: Array}>} 分页 AuditLogVO（createTime 降序）
 */
export function getAuditLogList(params = {}, { silent = false } = {}) {
  return request.get('/admin/audit-log/list', {
    params: pruneEmpty({ page: 1, size: ADMIN_PAGE_SIZE, ...params }),
    silent
  })
}

// ================================================================ 分类

/**
 * 新建分类（审计 CREATE_CATEGORY）
 * @param {{ name: string, sort?: number }} data name 必填 ≤50 且全局唯一，重复 → code=100「分类名称已存在」
 * @returns {Promise<{id: string}>} id 是 Long，序列化后是**字符串**
 */
export function createCategory(data) {
  return request.post('/admin/category', data)
}

/**
 * 修改分类（审计 UPDATE_CATEGORY）
 * @param {string|number} id
 * @param {{ name: string, sort?: number }} data
 */
export function updateCategory(id, data) {
  return request.put(`/admin/category/${id}`, data)
}

/**
 * 删除分类（审计 DELETE_CATEGORY）
 * ⚠️ 分类下还有商品 → code=208「分类下存在商品，请先迁移」（CategoryServiceImpl.java:117-121）
 */
export function deleteCategory(id) {
  return request.delete(`/admin/category/${id}`)
}

/**
 * 分类级联迁移：把源分类下所有商品迁到目标分类（审计 UPDATE_CATEGORY）
 * ⚠️ 源=目标 / 分类不存在 → code=100
 * @returns {Promise<{movedCount: number}>}
 */
export function migrateCategory(fromCategoryId, toCategoryId) {
  return request.put('/admin/category/migrate', { fromCategoryId, toCategoryId })
}

// ================================================================ 数据统计（批次 5.5.1）

/**
 * 统计接口的三个共同约定（写页面前必读）：
 *
 * ① **Long 一律是字符串**：JacksonConfig 把 Long 序列化成 String（防 JS 精度丢失），
 *    所以拿到手的是 `"100"` 而不是 `100`。计数值展示前要 Number()（计数不是 id，
 *    Number() 在这里是安全的；id / orderNo 才严禁转换）。
 * ② **后端有 60 秒缓存**（`admin:stats:*` 前缀，TTL 60s + 0~10s 抖动，无主动失效）：
 *    刚做的管理操作不会立刻反映到数字上，这是设计而非 bug，页面上已写明。
 * ③ **403 是 HTTP 200 + body.code=403**（非管理员）：页面必须单独渲染"无权限"态，
 *    而不是"加载失败"态。所以这三处都支持 `silent`，由页面自己出错误态。
 */

/**
 * 概览卡片：8 个数字一次取回
 *
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<{userTotal, userTodayNew, productTotal, productTodayNew,
 *   orderTotal, orderTodayNew, gmvTotal, gmvToday}>}
 *   计数是**字符串**，金额（gmvTotal / gmvToday）是 JSON number（BigDecimal 不做字符串化）
 */
export function getAdminStatsOverview({ silent = false } = {}) {
  return request.get('/admin/stats/overview', { silent })
}

/**
 * 订单状态分布：**恒定 8 条**（0~7 全量补齐，无数据 count=0）
 *
 * ⚠️ 响应里**没有 label**（后端不硬编码状态文案）：状态名一律由前端
 *    `orderStatusLabel(status)` 从 constants.js 的 ORDER_STATUS_MAP 取。
 *
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<Array<{status: number, count: string}>>}
 */
export function getAdminStatsOrderStatus({ silent = false } = {}) {
  return request.get('/admin/stats/order-status', { silent })
}

/**
 * 商品分类分布：以分类为主表，**空分类 count=0 也会返回**
 *
 * ⚠️ 孤儿商品（分类已被逻辑删除）是最后一条，且 `categoryId` / `categoryName` 会被
 *    Jackson 的 non_null 策略整个省略 → 拿到的是**字段缺失**而不是 null，
 *    判断用 `item.categoryId == null`（能同时兜住两种）。
 *
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<Array<{categoryId?: string, categoryName?: string, count: string}>>}
 */
export function getAdminStatsProductCategory({ silent = false } = {}) {
  return request.get('/admin/stats/product-category', { silent })
}

/**
 * 统计计数的归一化：后端 `Long → String`（JacksonConfig），拿到的是 "5" 而不是 5。
 *
 * ⚠️ 转换**只在本层做**（需求明确：不要在组件层再转换一次）：
 *    组件里拿到的就是干净的 number，可以放心做加法、比较、喂给 ECharts。
 *    注意与「禁止 Number(id)」不冲突 —— 那条规则针对 id / orderNo 这类标识，
 *    计数是安全的小整数。
 */
function toCount(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

/** 把后端返回的字符串数组归一化成 number 数组（非数组一律给空数组，绝不返回 null） */
function toCountArray(value) {
  return Array.isArray(value) ? value.map(toCount) : []
}

/**
 * 统计 · 趋势数据（批次 5.5.2）
 *
 * @param {number} [days=7] 统计天数，**白名单 7 / 30**（其它值后端 code=100）
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<{days: number, dates: string[], orderCounts: number[],
 *   productCounts: number[], userCounts: number[]}>}
 *   · `dates` 已是后端序列化好的 `yyyy-MM-dd` 字符串，**前端不要再格式化**，直接喂 xAxis.data
 *   · 三个 counts 数组与 dates **严格等长**（后端缺日期补 0），可直接当序列用
 */
export async function getAdminStatsTrend(days = 7, { silent = false } = {}) {
  const data = await request.get('/admin/stats/trend', { params: { days }, silent })
  return {
    days: toCount(data?.days ?? days),
    dates: Array.isArray(data?.dates) ? data.dates.map(String) : [],
    orderCounts: toCountArray(data?.orderCounts),
    productCounts: toCountArray(data?.productCounts),
    userCounts: toCountArray(data?.userCounts)
  }
}

/**
 * 统计 · 热门商品榜（批次 5.5.2）
 *
 * @param {number} [days=7]  窗口天数，白名单 7 / 30
 * @param {number} [limit=10] 返回条数，白名单 1~20
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<{days: number, items: Array<{productId: string, productTitle: string,
 *   categoryName: string, orderCount: number}>}>}
 *   · `productId` 是 Long → **字符串**，全程字符串（禁止 Number()）
 *   · `categoryName` 可能为 ''（后端 null：商品已删除 / 未分类 / 分类已删除），展示时由页面出「—」
 *   · 无数据时 items 是**空数组**
 */
export async function getAdminStatsHotProducts(days = 7, limit = 10, { silent = false } = {}) {
  const data = await request.get('/admin/stats/hot-products', { params: { days, limit }, silent })
  return {
    days: toCount(data?.days ?? days),
    items: Array.isArray(data?.items)
      ? data.items.map((item) => ({
          // id 全程字符串：一定要在归一化时 String() 保住它
          productId: item?.productId == null ? '' : String(item.productId),
          productTitle: item?.productTitle == null ? '' : String(item.productTitle),
          categoryName: item?.categoryName == null ? '' : String(item.categoryName),
          orderCount: toCount(item?.orderCount)
        }))
      : []
  }
}
