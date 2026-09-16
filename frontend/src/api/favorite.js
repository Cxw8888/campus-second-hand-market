/**
 * 收藏相关接口（对应后端 FavoriteController，前缀 /api/v1/favorite）
 * 全部为强制认证路径：未登录会返回 HTTP 401，由 request.js 统一跳登录页。
 */
import request from '@/utils/request'
import { pruneEmpty } from '@/utils/format'

/**
 * 收藏商品（唯一键幂等，重复收藏也返回成功）
 * @param {string|number} productId
 */
export function addFavorite(productId) {
  return request.post(`/favorite/${productId}`)
}

/**
 * 取消收藏（物理删除）
 * @param {string|number} productId
 */
export function removeFavorite(productId) {
  return request.delete(`/favorite/${productId}`)
}

/**
 * 是否已收藏
 * @returns {Promise<{ favorited: boolean }>}
 */
export function checkFavorite(productId, { silent = false } = {}) {
  return request.get(`/favorite/check/${productId}`, { silent })
}

/**
 * 我的收藏列表
 *
 * 注意：后端**不过滤**已删除/已下架商品，而是返回 productStatus 与 isDeleted，
 * 由前端标注「已失效」，所以列表里可能混着买不到的东西。
 *
 * @param {object} [params] page / size
 */
export function getFavoriteList(params = {}) {
  const { silent, ...rest } = params
  return request.get('/favorite/list', { params: pruneEmpty({ page: 1, size: 12, ...rest }), silent })
}
