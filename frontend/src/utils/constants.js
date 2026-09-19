/**
 * 全局常量与字典
 *
 * 字典（成色、交易方式、状态）都从后端 VO 的字段注释逐一对照抄写，
 * 避免前后端各写一套导致标签显示不一致。
 */

// ------------------------------------------------------------------ 分页
export const DEFAULT_PAGE_SIZE = 12
export const MAX_PAGE_SIZE = 100 // 后端 ProductQuery 限制 size <= 100，超了返回 code=100

/** 订单列表每页条数（需求指定 10） */
export const ORDER_PAGE_SIZE = 10

/** 我的商品每页条数 */
export const MY_PRODUCT_PAGE_SIZE = 12

/** 我的收藏每页条数 */
export const FAVORITE_PAGE_SIZE = 12

/** 消息中心每页条数 */
export const NOTIFICATION_PAGE_SIZE = 10

/** 商品图片上限（与后端 ProductSaveRequest @Size(max=9) 对齐） */
export const MAX_PRODUCT_IMAGES = 9

/** 商品标题长度上限（后端 1-100） */
export const PRODUCT_TITLE_MAX = 100

/** 商品描述长度上限（后端 ≤5000，前端给个更克制的建议值） */
export const PRODUCT_DESCRIPTION_MAX = 5000

/** 面交地点长度上限（后端 ≤100） */
export const TRADE_LOCATION_MAX = 100

/**
 * 「我的商品」页的 Tab 定义
 * status=null 表示不传该参数（后端查全部）
 */
export const MY_PRODUCT_TABS = [
  { key: 'all', label: '全部', status: null },
  { key: 'on', label: '上架中', status: 1 },
  { key: 'pending', label: '待审核', status: 3 },
  { key: 'off', label: '已下架', status: 0 },
  { key: 'soldout', label: '已售罄', status: 2 }
]

/**
 * 待支付订单的超时取消窗口（分钟）—— **后端事实**在前端的镜像。
 *
 * 后端（批次 6.0.6 · Minor 3 起）按**订单快照**的 trade_type 分档：
 *   trade_type = 1（仅面交）        → app.task.timeout-cancel.face-minutes，默认 120
 *   trade_type IN (2,3)（邮寄/皆可）→ app.task.timeout-cancel.minutes，默认 15
 * 后端 ScheduledTasks.cancelTimeoutOrders 每分钟扫一次，把 status=0 且超过窗口的订单置为
 * 4-已取消 并回补库存。**改了后端配置，这两个常量要跟着改。**
 *
 * ⚠️ 前端只是展示镜像，真正的取消动作永远由后端定时任务执行 —— 前端不做任何状态推进，
 *    判断"到底有没有超时"一律以后端返回的 status 为准。
 */
export const PAY_TIMEOUT_MAIL_MINUTES = 15
export const PAY_TIMEOUT_FACE_MINUTES = 120

/**
 * @deprecated 仅为兼容既有引用保留（等于邮寄窗口 15 分钟）。
 *
 * 新代码请用 {@link payTimeoutMinutes}：面交单的窗口是 120 分钟，
 * 直接用本常量会把面交单的倒计时/文案算成 15 分钟 —— 6.0.7 修的正是这个 bug。
 */
export const PAY_TIMEOUT_MINUTES = PAY_TIMEOUT_MAIL_MINUTES

/**
 * 按订单快照的 trade_type 返回待支付超时分钟数。
 *
 * @param {number|string} tradeType 1=仅面交（120 分钟）；2=仅邮寄 / 3=面交邮寄皆可（15 分钟，与后端 IN (2,3) 一致）
 * @returns {number} 超时分钟数（未知/未提供时按邮寄档 15 分钟兜底）
 */
export function payTimeoutMinutes(tradeType) {
  return Number(tradeType) === 1 ? PAY_TIMEOUT_FACE_MINUTES : PAY_TIMEOUT_MAIL_MINUTES
}

/** 待支付提示文案（按 trade_type 给出具体分钟数）：如「请在 120 分钟内完成支付，超时将自动取消」 */
export function payTimeoutHint(tradeType) {
  return `请在 ${payTimeoutMinutes(tradeType)} 分钟内完成支付，超时将自动取消`
}

/** 已超时文案（按 trade_type 给出具体分钟数）：如「超过 15 分钟未支付」 */
export function payTimeoutExpiredHint(tradeType) {
  return `超过 ${payTimeoutMinutes(tradeType)} 分钟未支付`
}

// ------------------------------------------------------------------ 分类
/**
 * 左侧分类侧边栏。
 *
 * 这里的 id 与数据库 tb_category 的 seed 数据严格一一对应（已核对线上库）：
 *   1-教材书籍  2-数码电子  3-生活用品  4-运动户外  5-服饰鞋包  6-其他闲置
 * 之所以硬编码而不调 GET /category/list：接口每次要联网，首屏会闪一下；
 * 而且 seed 分类是稳定的。若后端新增分类，只需在 constants.js 里补一行。
 * 5.4 起作为接口失败/首屏兜底，不再作为唯一数据源。
 */
export const CATEGORIES = [
  { id: 1, name: '教材书籍' },
  { id: 2, name: '数码电子' },
  { id: 3, name: '生活用品' },
  { id: 4, name: '运动户外' },
  { id: 5, name: '服饰鞋包' },
  { id: 6, name: '其他闲置' }
]

// ------------------------------------------------------------------ 成色
/** 成色：1-全新, 2-几乎全新, 3-轻微使用痕迹, 4-明显使用痕迹 */
export const CONDITION_OPTIONS = [
  { value: 1, label: '全新', tone: 'green' },
  { value: 2, label: '几乎全新', tone: 'blue' },
  { value: 3, label: '轻微使用', tone: 'orange' },
  { value: 4, label: '明显使用', tone: 'gray' }
]

const CONDITION_MAP = new Map(CONDITION_OPTIONS.map((it) => [it.value, it]))

/** 成色 → 文案（未知值兜底为「成色未知」，不留空白） */
export function conditionLabel(level) {
  return CONDITION_MAP.get(Number(level))?.label ?? '成色未知'
}

/** 成色 → 标签色调，供 ConditionTag 组件取色 */
export function conditionTone(level) {
  return CONDITION_MAP.get(Number(level))?.tone ?? 'gray'
}

// ------------------------------------------------------------------ 交易方式
/**
 * 交易方式：1-仅面交, 2-仅邮寄, 3-两者皆可
 *
 * tone 是全站交易方式标签的**唯一**配色来源（由 TradeTypeTag 组件消费）：
 *   面交 → 绿（校园主流）｜邮寄 → 蓝（需物流）｜皆可 → 橙（留给买家选）
 */
export const TRADE_TYPE_MAP = {
  1: { label: '仅面交', tone: 'green' },
  2: { label: '仅邮寄', tone: 'blue' },
  3: { label: '面交/邮寄', tone: 'orange' }
}

/** 交易方式 → 标签色调（供 TradeTypeTag 取色） */
export function tradeTypeTone(type) {
  return TRADE_TYPE_MAP[Number(type)]?.tone ?? 'gray'
}

export function tradeTypeLabel(type) {
  return TRADE_TYPE_MAP[Number(type)]?.label ?? '方式待定'
}

// ------------------------------------------------------------------ 商品状态
/**
 * 状态：0-下架/审核不通过, 1-上架中, 2-售罄, 3-待审核
 *
 * tone 决定标签配色，与订单状态共用同一套语义：
 *   绿=正向（在售）、蓝=进行中（待审核）、橙=需要注意（已售罄）、灰=终止（已下架）
 */
export const PRODUCT_STATUS_MAP = {
  0: { label: '已下架', tone: 'gray' },
  1: { label: '在售', tone: 'green' },
  2: { label: '已售罄', tone: 'orange' },
  3: { label: '待审核', tone: 'blue' }
}

export function productStatusLabel(status) {
  return PRODUCT_STATUS_MAP[Number(status)]?.label ?? '状态未知'
}

/** 商品状态 → 标签色调（供 MyProductCard 取色） */
export function productStatusTone(status) {
  return PRODUCT_STATUS_MAP[Number(status)]?.tone ?? 'gray'
}

// ------------------------------------------------------------------ 收藏项可用性
/**
 * 收藏项的展示状态与「能否下单」判定（纯函数，便于单测）
 *
 * 校园二手特征：**库存常为 1，售罄是高频状态**，所以「已售罄」必须和「已下架」「已删除」
 * 一样作为"失效"处理，不能只判 status=0。
 *
 * 依据后端 FavoriteVO 的真实字段（已读源码核实）：
 *   · productStatus：商品当前状态 0-下架 / 1-上架 / 2-售罄 / 3-待审核
 *   · isDeleted    ：商品是否已被逻辑删除（Boolean，**不是** 0/1 数字）
 *   · available    ：后端已经算好的「是否仍可下单」= status=1 && !isDeleted
 *
 * 「能否下单」以后端下发的 available 为准（单一事实来源）；后端没给时才按状态兜底推算。
 *
 * @param {object} item FavoriteVO
 * @returns {{label:string, tone:string, orderable:boolean, deleted:boolean, status:number|null}}
 */
export function resolveFavoriteState(item) {
  const deleted = Boolean(item?.isDeleted)
  const status = item?.productStatus == null ? null : Number(item.productStatus)

  let label = '待审核'
  let tone = 'blue'
  if (deleted) {
    label = '已失效'
    tone = 'gray'
  } else if (status === 0) {
    label = '已下架'
    tone = 'gray'
  } else if (status === 2) {
    label = '已售罄'
    tone = 'orange'
  } else if (status === 1) {
    label = '在售'
    tone = 'green'
  }

  const orderable =
    typeof item?.available === 'boolean' ? item.available : !deleted && status === 1

  return { label, tone, orderable, deleted, status }
}

// ------------------------------------------------------------------ 站内信类型
/**
 * 站内信类型字典（校园二手语义）
 *
 * 已读源码核实（NotificationVO + AdminServiceImpl 常量 + 各 sendAsync 调用点）：
 *   type=1 订单通知 → bizType=1，bizId = **订单 ID**（下单/支付/发货/收货/退款都用它）
 *   type=2 审核通知 → bizType=2，bizId = **商品 ID**（管理员审核通过/驳回）
 *   type=3 系统通知 → bizId 无意义（后端默认 0）
 *
 * ⚠️ bizId=0 表示「没有关联业务对象」——例如封禁通知虽然 type=1，但 bizId 传的是 0，
 *    这种情况绝不能跳 /order/detail/0。跳转前必须判 bizId 是否有效。
 *
 * icon 名与 Element Plus 实际导出核对过（本项目有图标检查环节）：
 *   · 需求写的是「购物袋 / 盾牌 / 喇叭」，但 **EP 没有盾牌图标**（Shield、ShieldCheck 都不存在），
 *     也没有喇叭图标。因此审核通知改用 `Stamp`（印章，更贴合中文「审核盖章」语义），
 *     系统通知改用 `Bell`（通用通知语义，且与导航栏「消息中心」图标一致）。
 */
export const NOTIFICATION_TYPE_MAP = {
  1: { label: '订单通知', icon: 'ShoppingBag', tone: 'green', jump: 'order' },
  2: { label: '审核通知', icon: 'Stamp', tone: 'blue', jump: 'product' },
  3: { label: '系统通知', icon: 'Bell', tone: 'gray', jump: null }
}

export function notificationTypeLabel(type) {
  return NOTIFICATION_TYPE_MAP[Number(type)]?.label ?? '通知'
}

export function notificationIconName(type) {
  return NOTIFICATION_TYPE_MAP[Number(type)]?.icon ?? 'Bell'
}

/** 通知色调（用于图标底色） */
export function notificationTone(type) {
  return NOTIFICATION_TYPE_MAP[Number(type)]?.tone ?? 'gray'
}

/**
 * 通知点击后该跳去哪（命名路由 + 参数），无需跳转时返回 null
 *
 * @returns {{name:string, params:object}|null}
 */
export function resolveNotificationTarget(notification) {
  const meta = NOTIFICATION_TYPE_MAP[Number(notification?.type)]
  if (!meta?.jump) return null

  // bizId 是 Long→String 序列化的字符串；0 表示无关联业务对象，不跳转
  const bizId = notification?.bizId
  if (bizId == null || String(bizId) === '' || String(bizId) === '0') return null

  if (meta.jump === 'order') return { name: 'order-detail', params: { orderId: String(bizId) } }
  if (meta.jump === 'product') return { name: 'product-detail', params: { id: String(bizId) } }
  return null
}

// ------------------------------------------------------------------ 排序
/**
 * 顶部筛选区的排序选项。
 * 取值必须落在后端白名单内（ProductQuery：sortBy ∈ {price, create_time}、order ∈ {asc, desc}），
 * 否则会返回 code=100。
 */
export const SORT_OPTIONS = [
  { value: 'create_time-desc', label: '最新发布' },
  { value: 'price-asc', label: '价格从低到高' },
  { value: 'price-desc', label: '价格从高到低' }
]

/** 把 'price-asc' 这种 UI 值拆成接口需要的两个参数 */
export function parseSort(value) {
  const [sortBy = 'create_time', order = 'desc'] = String(value || '').split('-')
  return { sortBy, order }
}

// ------------------------------------------------------------------ 订单状态
/**
 * 订单状态字典（8 种，配色语义与设计规范一致）
 *
 *   0 待支付 = 橙      1 已支付 = 蓝      2 已发货 = 紫      3 已完成 = 绿
 *   4 已取消 = 灰      5 已冻结 = 灰(带锁) 6 退款申请中 = 黄   7 退款被拒 = 深橙
 *
 * actionHint 用于列表/详情页给出「下一步能做什么」的提示文案。
 *
 * ⚠️ 待支付（0）的文案**刻意不带具体分钟数**：窗口随订单 trade_type 变化（面交 120 / 邮寄 15），
 *    写在字典里必然有一边是错的（6.0.7 之前就写着"15 分钟"，面交单显示错误）。
 *    需要带分钟数时用 {@link orderStatusHint}(status, tradeType)。
 */
export const ORDER_STATUS_MAP = {
  0: { label: '待支付', tone: 'orange', icon: '', actionHint: '请在支付时限内完成支付，超时将自动取消' },
  1: { label: '已支付待发货', tone: 'blue', icon: '', actionHint: '等待卖家发货或约定面交' },
  2: { label: '已发货待收货', tone: 'purple', icon: '', actionHint: '收到货后请及时确认收货' },
  3: { label: '已完成', tone: 'green', icon: '', actionHint: '交易已完成，感谢使用' },
  4: { label: '已取消', tone: 'gray', icon: '', actionHint: '订单已取消，库存已回补' },
  5: { label: '已冻结', tone: 'frozen', icon: 'Lock', actionHint: '订单已冻结，请联系管理员处理' },
  6: { label: '退款申请中', tone: 'yellow', icon: '', actionHint: '等待卖家处理退款申请' },
  7: { label: '退款被拒', tone: 'darkorange', icon: '', actionHint: '退款被拒，可等待管理员介入' }
}

export function orderStatusLabel(status) {
  return ORDER_STATUS_MAP[Number(status)]?.label ?? '状态未知'
}

export function orderStatusTone(status) {
  return ORDER_STATUS_MAP[Number(status)]?.tone ?? 'gray'
}

/**
 * 订单状态提示文案。
 *
 * @param {number|string} status 订单状态
 * @param {number|string} [tradeType] 订单快照的 trade_type：给了它就按它算出待支付的具体分钟数
 *        （面交 120 / 邮寄 15）；不给则退回字典里的**通用文案**（不带分钟数，避免显示错误数字）。
 */
export function orderStatusHint(status, tradeType) {
  if (Number(status) === 0 && tradeType !== undefined && tradeType !== null && tradeType !== '') {
    return payTimeoutHint(tradeType)
  }
  return ORDER_STATUS_MAP[Number(status)]?.actionHint ?? ''
}

/** 订单是否处于「未完成」状态（后端 UNFINISHED_ORDER_STATUS = 0,1,2,6,7） */
export function isOrderUnfinished(status) {
  return [0, 1, 2, 6, 7].includes(Number(status))
}

// ------------------------------------------------------------------ 管理端（第五批）

/**
 * 管理端列表每页条数
 *
 * 后端 PageQuery：page 默认 1（@Min(1)）、size 默认 10（@Min(1) @Max(100)），
 * 越界直接 code=100（PageQuery.java:22-29）。
 */
export const ADMIN_PAGE_SIZE = 10

/**
 * 管理端商品页的状态页签（顺序即展示顺序：待审核排第一，因为那是管理员的主要待办）
 *
 * ⚠️ **刻意不做「全部」页签**，原因是后端行为而不是偷懒：
 *    AdminProductQuery.status 的字段默认值是 3（AdminProductQuery.java:19），
 *    而 ServiceImpl 是 `.eq(query.getStatus() != null, ...)`（AdminServiceImpl.java:95）——
 *    也就是说**省略参数 = 只查待审核，不是「不过滤」**。
 *    想查「全部」只能显式传空串 `?status=`（Spring 把空串转成 null 才不过滤），
 *    这个绑定行为在后端未启动的情况下我无法实测，与其做一个可能名不副实的「全部」，不如不做。
 *
 * 文案不在这里写死，统一走 productStatusLabel()（PRODUCT_STATUS_MAP 是唯一事实来源）。
 */
export const ADMIN_PRODUCT_STATUS_TABS = [3, 1, 0, 2]

/** 管理端用户状态：0-正常, 1-封禁（与 tb_user.status 对齐，AdminUserVO.java:39-40） */
export const ADMIN_USER_STATUS_MAP = {
  0: { label: '正常', tone: 'green' },
  1: { label: '已封禁', tone: 'gray' }
}

export function adminUserStatusLabel(status) {
  return ADMIN_USER_STATUS_MAP[Number(status)]?.label ?? '状态未知'
}

export function adminUserStatusTone(status) {
  return ADMIN_USER_STATUS_MAP[Number(status)]?.tone ?? 'gray'
}

/**
 * 审计操作类型：与后端 AuditOperationType 枚举**逐字对应**（AuditOperationType.java:8-42），共 11 个。
 *
 * ⚠️ 这些字符串**直接落库**，且查询时是精确匹配（AdminServiceImpl.java:306-307）：
 *    写错一个字母既不会报错，也查不出任何数据（表现为「筛选后永远是空列表」）。
 *    所以这里不允许自造值，也不允许在页面里手写字符串。
 */
export const AUDIT_OPERATION_TYPE_MAP = {
  APPROVE_PRODUCT: { label: '商品审核通过', tone: 'green' },
  REJECT_PRODUCT: { label: '商品审核不通过', tone: 'orange' },
  BAN_USER: { label: '封禁用户', tone: 'gray' },
  UNBAN_USER: { label: '解封用户', tone: 'green' },
  FORCE_OFFLINE: { label: '强制下架商品', tone: 'darkorange' },
  UNFREEZE_ORDER: { label: '解冻订单', tone: 'blue' },
  COMPLETE_ORDER: { label: '线下完成订单', tone: 'green' },
  CREATE_CATEGORY: { label: '新建分类', tone: 'blue' },
  UPDATE_CATEGORY: { label: '修改分类', tone: 'blue' },
  DELETE_CATEGORY: { label: '删除分类', tone: 'gray' },
  REFUND_ORDER: { label: '强制退款', tone: 'darkorange' }
}

/** 操作类型的全部合法取值（筛选下拉用，顺序即枚举声明顺序） */
export const AUDIT_OPERATION_TYPES = Object.keys(AUDIT_OPERATION_TYPE_MAP)

export function auditOperationLabel(type) {
  return AUDIT_OPERATION_TYPE_MAP[type]?.label ?? type ?? '未知操作'
}

export function auditOperationTone(type) {
  return AUDIT_OPERATION_TYPE_MAP[type]?.tone ?? 'gray'
}

/** 审计结果：1-成功, 0-失败（AuditLogVO.java:36-37） */
export const AUDIT_RESULT_MAP = {
  1: { label: '成功', tone: 'green' },
  0: { label: '失败', tone: 'orange' }
}

export function auditResultLabel(result) {
  return AUDIT_RESULT_MAP[Number(result)]?.label ?? '未知'
}

export function auditResultTone(result) {
  return AUDIT_RESULT_MAP[Number(result)]?.tone ?? 'gray'
}

/** 管理端角色：0-学生, 1-管理员（AdminUserVO.java:36-37 / UserVO.java:39-40） */
export const ADMIN_ROLE_MAP = {
  0: { label: '学生', tone: 'gray' },
  1: { label: '管理员', tone: 'blue' }
}

export function adminRoleLabel(role) {
  return ADMIN_ROLE_MAP[Number(role)]?.label ?? '未知角色'
}

export function adminRoleTone(role) {
  return ADMIN_ROLE_MAP[Number(role)]?.tone ?? 'gray'
}

/**
 * 语义色调 → Element Plus `el-tag` 的 type
 *
 * 全站字典里的 tone 是**语义名**（green/blue/orange/gray…），而 el-tag 只认
 * success/primary/warning/info/danger。把这张映射表集中放在这里，是为了保证
 * 「同一个语义在任何页面都是同一个颜色」；否则每个页面各写一套 if-else，
 * 迟早出现「gray 在审核日志里是 info、在用户列表里是 danger」这种漂移。
 */
export const TONE_TO_TAG_TYPE = {
  green: 'success',
  blue: 'primary',
  orange: 'warning',
  darkorange: 'danger',
  yellow: 'warning',
  purple: 'primary',
  gray: 'info',
  frozen: 'info'
}

export function tagTypeOf(tone) {
  return TONE_TO_TAG_TYPE[tone] ?? 'info'
}

/**
 * 管理端用户列表的状态页签：null = 全部
 *
 * ⚠️ 与商品列表**不同**：AdminUserQuery.status 没有字段默认值（AdminUserQuery.java:21-22），
 *    省略就是「不过滤」，所以这里可以做「全部」页签。
 */
export const ADMIN_USER_STATUS_FILTERS = [null, 0, 1]

/**
 * 管理端用户列表的角色筛选：null = 全部
 *
 * ⚠️ 后端 `AdminUserQuery` **没有 role 字段**（只有 keyword + status，AdminUserQuery.java:19-22），
 *    所以角色筛选**没有服务端支持**。页面的做法是：选中角色后一次性把当前筛选条件下的用户
 *    拉满（size = MAX_PAGE_SIZE = 100，也是后端 @Max 上限），再在前端按 role 过滤 + 前端分页；
 *    若库里用户总数超过 100，界面会明确提示"结果可能不完整"，不假装完整。
 */
export const ADMIN_ROLE_FILTERS = [null, 0, 1]

/**
 * 管理端订单列表的状态页签：null = 全部，其余取 ORDER_STATUS_MAP 的 8 个状态
 * （AdminOrderQuery.status 同样没有默认值 → 省略即全部，AdminOrderQuery.java:18-19）
 */
export const ADMIN_ORDER_STATUS_FILTERS = [null, ...Object.keys(ORDER_STATUS_MAP).map(Number)]

/**
 * 管理端订单页签的补充说明
 *
 * 面交订单不经过「已发货」（0→1→3，或 0→3），所以状态 2 在管理端**只可能是邮寄订单**；
 * 在页签上点明这一点，管理员就不会因为"已发货却没有物流信息"而困惑。
 *
 * ⚠️ 只影响**管理端页签文案**，不动 ORDER_STATUS_MAP ——
 *    那个字典还被学生侧的 OrderStatusTag 使用，改它等于改了全站标签。
 */
export const ADMIN_ORDER_STATUS_TAB_NOTES = { 2: '（仅邮寄）' }

export function adminOrderStatusTabLabel(status) {
  if (status === null) return '全部'
  return `${orderStatusLabel(status)}${ADMIN_ORDER_STATUS_TAB_NOTES[status] ?? ''}`
}

/** 分类名长度上限（后端 CategorySaveRequest.java:20 是 @Size(max=50)） */
export const CATEGORY_NAME_MAX = 50

// ------------------------------------------------------------------ 管理端数据统计（5.5.1）

/**
 * 统计结果的缓存时长（秒）—— 后端 `AdminStatsServiceImpl` 的 CACHE_TTL_SECONDS 镜像。
 *
 * ⚠️ 与 PAY_TIMEOUT_MINUTES 一样，这是**后端事实**在前端的镜像：后端统计接口
 *    （/api/v1/admin/stats/*）走 `admin:stats:*` 缓存，TTL 60 秒 + 0~10 秒随机抖动，
 *    **没有主动失效**（统计是只读的，60 秒自然过期足够）。
 *    所以页面上必须写明"数据可能最多滞后 60 秒"，否则管理员刚封完号发现数字没动会以为坏了。
 *    改了后端 TTL，这里要跟着改。
 */
export const ADMIN_STATS_CACHE_SECONDS = 60

/**
 * 商品分类分布里「分类已被逻辑删除」的孤儿商品的展示名。
 *
 * 后端对这类商品返回的 categoryId / categoryName 是 null（Jackson 的 non_null 策略下
 * 字段会整个消失），**文案必须由前端出**：后端不硬编码任何展示文案，只有数字与名称。
 */
export const ADMIN_STATS_UNCATEGORIZED_LABEL = '未分类（分类已删除）'

/**
 * 趋势图可切换的天数档位（批次 5.5.2）。
 *
 * ⚠️ 必须与后端 `AdminStatsServiceImpl.ALLOWED_WINDOW_DAYS` 白名单**逐字对齐**：
 *    后端只接受 7 与 30，传别的值（例如 15）直接 code=100。
 *    加档位要两边一起改，否则页面上会出现一个点了就报错的按钮。
 */
export const ADMIN_STATS_TREND_RANGES = [7, 30]

/**
 * 热门榜的窗口天数（批次 5.5.2 决策 2：固定「近 7 天」，页面上不做切换）。
 *
 * 接口本身保留 `days` 参数以便将来扩展，但前端只传这一个值；
 * 页面上所有"近 N 天"的文案都从这里取，避免同一个数字写在三处。
 */
export const ADMIN_STATS_HOT_DAYS = 7

/** 热门榜条数（与后端 limit 白名单 1~20 内；固定 Top 10） */
export const ADMIN_STATS_HOT_LIMIT = 10

/** 热门榜「分类名缺失」时的占位符（后端返回 null，文案由前端出） */
export const ADMIN_STATS_EMPTY_CELL = '—'

/**
 * 分类排序权重的前端兜底范围
 *
 * ⚠️ 后端 `CategorySaveRequest.sort` **没有 @Min/@Max**（只有 `Integer sort = 0` 默认值，
 *    且 ServiceImpl 会把 null 兜成 0：CategoryServiceImpl.java:83/104）。
 *    所以 0 ~ 9999 是**纯前端兜底**，不是后端约束 —— 目的是防止管理员手滑输入负数/超大值
 *    导致列表排序语义失控（数据量大时排序值会有明显的主次关系）。
 */
export const ADMIN_CATEGORY_SORT_MIN = 0
export const ADMIN_CATEGORY_SORT_MAX = 9999

/**
 * 审计日志的「目标类型」字典
 *
 * 取值来自后端 `AdminAuditService.record(...)` 的 targetType 实参（javadoc 明确列出
 * PRODUCT / USER / ORDER / CATEGORY，AdminAuditService.java:43），调用点见
 * AdminServiceImpl / AdminCategoryController —— 是**字符串常量**，不是枚举，前端不能自造。
 */
export const AUDIT_TARGET_TYPE_MAP = {
  PRODUCT: '商品',
  USER: '用户',
  ORDER: '订单',
  CATEGORY: '分类'
}

export function auditTargetTypeLabel(type) {
  if (!type) return '—'
  return AUDIT_TARGET_TYPE_MAP[type] ?? type
}

// ------------------------------------------------------------------ 业务响应码（按需补充）
/** 与后端 ErrorCode 对齐，只列出前端会做特殊处理的部分 */
export const CODE = {
  SUCCESS: 200,
  PARAM_ERROR: 100,
  LOGIN_FAILED: 101,
  ACCOUNT_LOCKED: 104,
  EMAIL_CODE_TOO_FREQUENT: 106,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  STOCK_NOT_ENOUGH: 201,
  REPEAT_SUBMIT: 202,
  NO_PERMISSION: 203,
  PRODUCT_NOT_AVAILABLE: 204,
  USER_BANNED: 205,
  REFUND_REJECTED_WAIT_APPEAL: 206,
  PRODUCT_HAS_ORDER: 207,
  CATEGORY_HAS_PRODUCT: 208,
  STATUS_NOT_ALLOWED: 209
}
