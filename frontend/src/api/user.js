/**
 * 用户接口（对应后端 UserController，前缀 /api/v1/user，全部强制认证）
 */
import request from '@/utils/request'
import { pruneEmpty } from '@/utils/format'

/**
 * 查询个人资料（password 永不返回）
 *
 * @param {{ silent?: boolean }} [options] silent=true 时不弹错误提示（守卫兜底确认身份时用）
 * @returns {Promise<{id, username, nickname, avatar, phone, email, role, status}>}
 *   ⚠️ 注意主键字段名是 **id**（UserVO），而 LoginVO 里叫 **userId** —— 合并进 store 时要显式映射
 */
export function getProfile({ silent = false } = {}) {
  return request.get('/user/profile', { silent })
}

/**
 * 更新个人资料（后端是**非全量**更新：只更新传入的字段）
 * @param {{ nickname?: string, avatar?: string, phone?: string }} data
 */
export function updateProfile(data, { silent = false } = {}) {
  return request.put('/user/profile', pruneEmpty(data), { silent })
}

/**
 * 修改密码（后端是 PUT；校验旧密码；新密码不得与旧密码相同；成功后该用户全部 Token 失效）
 * @param {{ oldPassword: string, newPassword: string }} data
 */
export function changePassword(data) {
  return request.put('/user/password', data)
}

/**
 * 换绑邮箱（校验登录密码 + 新邮箱验证码；成功后该用户全部 Token 失效）
 *
 * ⚠️ 注意：后端 ChangeEmailRequest 需要三个字段 —— newEmail / emailCode / **password**（登录密码），
 * 少传 password 会直接 code=100。
 *
 * @param {{ newEmail: string, emailCode: string, password: string }} data
 */
export function changeEmail(data) {
  return request.post('/user/change-email', data)
}
