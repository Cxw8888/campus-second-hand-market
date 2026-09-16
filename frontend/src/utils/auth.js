/**
 * Token 存储（localStorage）
 *
 * 为什么单独一个文件、而不是直接读 Pinia：
 *   axios 拦截器（utils/request.js）需要拿 token，而 store 又会 import api 层、
 *   api 层又 import request.js —— 直接互相 import 会形成循环依赖。
 *   所以这里把「token 的落盘」独立出来当唯一的持久化出口：
 *     · store 负责响应式状态，并在登录/登出时调用这里
 *     · request.js 只依赖这里读 token
 *   两边都不需要 import 对方，依赖是单向的。
 */

const TOKEN_KEY = 'cm-token'

/** 读取 token；不存在返回空字符串（方便直接做真值判断） */
export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用，降级为「无 token」
    return ''
  }
}

/** 写入 token */
export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 忽略：无法持久化时仍可用内存态跑完当前会话 */
  }
}

/** 清除 token（登出 / 401 失效时调用） */
export function clearToken() {
  setToken('')
}
