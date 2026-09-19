/**
 * Axios 封装：请求 / 响应拦截器
 *
 * 后端统一响应体是 { code, msg, data }（见 Result.java），规则：
 *   · 业务接口一律 HTTP 200，业务成败看 body.code
 *   · 只有「未登录 / Token 失效」才返回真实 HTTP 401
 *
 * 于是这里定两条约定：
 *   1. 响应拦截器把 body.data 直接「脱壳」返回，业务代码里 `const data = await getProductList()`
 *      拿到的就是 data 本身，不用层层 .data.data。
 *   2. 业务失败（code !== 200）统一弹 ElMessage 并 reject 一个带 code 的错误，
 *      调用方若需要自己处理（比如列表页降级到 mock），传 `{ silent: true }` 即可静默。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearToken } from './auth'
import { CODE } from './constants'

/**
 * 业务异常：把后端的 code / msg 带出来，便于调用方按码分支处理。
 */
export class BizError extends Error {
  constructor(code, msg) {
    super(msg || '请求失败')
    this.name = 'BizError'
    this.code = code
  }
}

/** 网络层异常（后端没起、超时等），单独区分于业务异常 */
export class NetworkError extends Error {
  constructor(message) {
    super(message)
    this.name = 'NetworkError'
  }
}

const service = axios.create({
  // 走 Vite 代理 → http://127.0.0.1:8080/api/v1
  baseURL: '/api/v1',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' }
})

// ------------------------------------------------------------------ 请求拦截器
service.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// ------------------------------------------------------------------ 401 处理
/**
 * 登录态失效：清 token 并跳登录页。
 *
 * 这里用「动态 import」而不是顶部静态 import router：
 * router → stores/user → api/auth → utils/request，静态引入会绕成循环依赖。
 * 动态 import 只在真正 401 时才求值，彻底避开环。
 */
async function handleUnauthorized() {
  clearToken()
  try {
    // 同步把 Pinia 里的登录态也清掉：否则顶部导航还挂着上半场的头像/昵称。
    // 同样用动态 import 规避循环依赖（request → stores/user → api/auth → request）。
    const { useUserStore } = await import('@/stores/user')
    useUserStore().reset()
  } catch {
    // pinia 尚未就绪（极早期请求）：忽略，localStorage 已经清干净了
  }
  try {
    const { default: router } = await import('@/router')
    const current = router.currentRoute.value
    // 已经在登录/注册页就不再重复跳，避免死循环
    if (current.name === 'login' || current.name === 'register') return
    router.replace({
      name: 'login',
      query: { redirect: current.fullPath }
    })
  } catch {
    // 路由还没就绪（极少见）：退回最朴素的方式
    if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
      window.location.href = '/login'
    }
  }
}

// ------------------------------------------------------------------ 响应拦截器
service.interceptors.response.use(
  (response) => {
    const body = response.data

    // 非统一响应体（文件流 / 代理来的静态资源等）原样返回
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    // 成功：脱壳，直接给 data
    if (body.code === CODE.SUCCESS) {
      return body.data
    }

    // 业务失败：按需提示 + reject
    const silent = response.config?.silent === true
    if (!silent) {
      ElMessage.error(body.msg || '操作失败')
    }
    return Promise.reject(new BizError(body.code, body.msg))
  },

  async (error) => {
    const { response, config } = error
    const silent = config?.silent === true

    // ① 后端有响应：HTTP 401 = 未登录 / Token 失效（业务码仍是 401，见 ErrorCode）
    if (response) {
      if (response.status === 401) {
        if (!silent) {
          ElMessage.warning(response.data?.msg || '登录已失效，请重新登录')
        }
        await handleUnauthorized()
        return Promise.reject(new BizError(CODE.UNAUTHORIZED, response.data?.msg || '登录已失效'))
      }

      const msg =
        response.data?.msg ||
        (response.status === 404 ? '接口不存在（请确认后端已启动）' : `请求失败（HTTP ${response.status}）`)
      if (!silent) ElMessage.error(msg)
      return Promise.reject(new BizError(response.status, msg))
    }

    // ② 压根没连上：后端未启动 / 被防火墙拦 / 超时
    const isTimeout = error.code === 'ECONNABORTED'
    // 文案分环境（批次文档修复批 · 自审 Minor 21）：
    //   修前无视环境一律提示"请确认 http://127.0.0.1:8080 已启动" ——
    //   生产 bundle 里带着内网地址，既向使用者暴露了拓扑、又是一句无从执行的建议。
    //   开发环境保留这条精确提示（本机联调时最有用），生产只给通用文案。
    const connectHint = import.meta.env.DEV
      ? '无法连接后端服务，请确认 http://127.0.0.1:8080 已启动'
      : '无法连接服务，请检查网络后重试'
    const msg = isTimeout ? '请求超时，请稍后重试' : connectHint
    if (!silent) ElMessage.error(msg)
    return Promise.reject(new NetworkError(msg))
  }
)

export default service
