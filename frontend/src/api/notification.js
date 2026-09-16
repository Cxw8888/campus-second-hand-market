/**
 * 站内信接口（对应后端 NotificationController，前缀 /api/v1/notification）
 */
import request from '@/utils/request'
import { pruneEmpty } from '@/utils/format'

/**
 * 未读消息数（个人中心的角标用；后端注释建议前端 30 秒轮询）
 * @returns {Promise<{ count: string }>} count 是 Long→String，比较前记得 Number()
 */
export function getUnreadCount({ silent = true } = {}) {
  return request.get('/notification/unread-count', { silent })
}

/**
 * 消息列表
 * @param {object} [params] isRead / page / size
 */
export function getNotificationList(params = {}) {
  const { silent, ...rest } = params
  return request.get('/notification/list', { params: pruneEmpty({ page: 1, size: 10, ...rest }), silent })
}

/**
 * 单条标记已读
 *
 * silent 默认 **true**：这个接口绝大多数场景是「用户点开消息」时顺手触发的后台行为，
 * 不是用户主动发起的操作，失败不该弹 toast 打断他（需求明确要求「失败静默」）。
 */
export function markNotificationRead(id, { silent = true } = {}) {
  return request.put(`/notification/read/${id}`, null, { silent })
}

/** 全部标记已读（这个是用户主动点的，失败要提示，所以 silent 默认 false） */
export function markAllNotificationsRead({ silent = false } = {}) {
  return request.put('/notification/read-all', null, { silent })
}
