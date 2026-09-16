/**
 * 认证相关接口（对应后端 AuthController，前缀 /api/v1/auth）
 */
import request from '@/utils/request'

/**
 * 登录
 * @param {{ username: string, password: string }} data
 * @returns {Promise<{token, tokenType, expiresIn, userId, username, nickname, avatar, role}>}
 */
export function login(data) {
  return request.post('/auth/login', data)
}

/**
 * 注册（用户名即学号）
 * @param {{ username, password, nickname, email, emailCode }} data
 * @returns {Promise<{ userId: string }>}
 */
export function register(data) {
  return request.post('/auth/register', data)
}

/**
 * 退出登录（单 Token 黑名单）
 */
export function logout() {
  return request.post('/auth/logout')
}

/**
 * 获取邮箱验证码
 *
 * 后端关键行为：app.email.skip=true（毕设降级）时，验证码会直接在 data.code 里返回，
 * 不需要真的收邮件。所以登录/注册页要把 data.code 醒目地展示出来。
 *
 * @param {string} email 校园邮箱
 * @param {'REGISTER'|'RESET_PASSWORD'|'BIND_EMAIL'} scene 场景
 * @returns {Promise<{ skip: boolean, code: string|null, expireSeconds: number }>}
 */
export function sendEmailCode(email, scene = 'REGISTER') {
  return request.get('/auth/email-code', { params: { email, scene } })
}
