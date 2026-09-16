/**
 * 商品相关接口（对应后端 ProductController，前缀 /api/v1/product）
 *
 * 注意：/list 与 /detail 都是「可选认证」——游客能看，登录后可见性更高。
 * 所以这两个接口不带 token 也能正常返回。
 */
import request from '@/utils/request'
import { DEFAULT_PAGE_SIZE, MY_PRODUCT_PAGE_SIZE } from '@/utils/constants'
import { pruneEmpty } from '@/utils/format'

/**
 * 商品列表（游客视角：仅 status=1 上架中）
 *
 * @param {object} params
 * @param {number} [params.page=1]          页码，从 1 开始
 * @param {number} [params.size=12]         每页条数，后端上限 100
 * @param {string} [params.keyword]         标题/描述模糊匹配
 * @param {number} [params.categoryId]      分类 id
 * @param {number} [params.minPrice]        最低价
 * @param {number} [params.maxPrice]        最高价
 * @param {'create_time'|'price'} [params.sortBy]  排序字段白名单
 * @param {'asc'|'desc'} [params.order]            排序方向白名单
 * @param {boolean} [params.silent]         出错时不弹 ElMessage（列表页降级用）
 * @returns {Promise<{ total, pages, current, size, records: Array }>}
 *          ⚠️ total/pages/current/size 被后端 Long→String 序列化，是字符串，比较前记得 Number()
 */
export function getProductList(params = {}) {
  const { silent, ...query } = params
  return request.get('/product/list', {
    params: pruneEmpty({ size: DEFAULT_PAGE_SIZE, page: 1, ...query }),
    silent
  })
}

/**
 * 商品详情（可见性分级：游客仅上架中；卖家本人 / 管理员可见全部状态）
 * @param {string|number} id 商品 id
 */
export function getProductDetail(id, { silent = false } = {}) {
  return request.get(`/product/detail/${id}`, { silent })
}

/**
 * 分类列表（**可选认证**，游客也能看；按 sort 升序、id 升序）
 *
 * 对应后端 GET /api/v1/category/list（CategoryController.java:32-35）→ `List<CategoryVO>`，**不分页**。
 *
 * 说明：C 端首屏用的是 constants.js 里硬编码的 `CATEGORIES`（避免首屏联网闪一下，见那里的注释）；
 * 管理端分类管理必须用**实时**数据（增删改后要立刻看到），所以走这个函数。
 *
 * @param {{ silent?: boolean }} [options]
 * @returns {Promise<Array<{id: string, name: string, sort: number}>>}
 *   ⚠️ id 是 Long → 序列化成**字符串**，一律当字符串用
 */
export function getCategoryList({ silent = false } = {}) {
  return request.get('/category/list', { silent })
}

/**
 * 发布商品（强制认证，落库 status=3 待审核）
 *
 * ⚠️ PUT/POST 的商品请求体是**全量**语义，9 个字段都要给：
 *    categoryId / title / description / price / stock / conditionLevel / tradeType / tradeLocation / imageUrls
 *    其中 imageUrls 必须非空（1-9 张），前端要先调 /api/v1/upload/image 拿到 URL 列表。
 */
export function createProduct(data, { silent = false } = {}) {
  return request.post('/product', data, { silent })
}

/**
 * 编辑商品（PUT 全量更新；关键字段变更会重置为待审核，详见 ProductForm 的注释）
 * @param {string} id 商品 id（字符串，勿转数字）
 */
export function updateProduct(id, data, { silent = false } = {}) {
  return request.put(`/product/${id}`, data, { silent })
}

/**
 * 我的商品（卖家视角，含待审核）
 * @param {object} [params] status(0-3) / page / size
 */
export function getMyProducts(params = {}) {
  const { silent, ...query } = params
  return request.get('/product/my', {
    params: pruneEmpty({ page: 1, size: MY_PRODUCT_PAGE_SIZE, ...query }),
    silent
  })
}

/**
 * 下架自己的商品（1→0）
 * @param {string} id
 */
export function offShelfProduct(id) {
  return request.put(`/product/off-shelf/${id}`)
}

/**
 * 删除商品（存在未完成订单时后端返回 code=207）
 * @param {string} id
 */
export function deleteProduct(id, { silent = false } = {}) {
  return request.delete(`/product/${id}`, { silent })
}
