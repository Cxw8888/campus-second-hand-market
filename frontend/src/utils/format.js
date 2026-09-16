/**
 * 展示层格式化工具
 */

/**
 * 价格格式化。
 *
 * ⚠️ 后端 JacksonConfig 只把 Long 序列化成字符串，BigDecimal 仍是 JSON number，
 * 所以 price 拿到的是 25 / 25.0 这种数字，需要统一补两位小数。
 * 防御性处理：String 也接得住（万一以后改成字符串序列化）。
 */
export function formatPrice(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '0.00'
  return num.toFixed(2)
}

/**
 * 时间展示：后端 LocalDateTime 序列化为 "yyyy-MM-dd HH:mm:ss"。
 * 列表里只显示到「日」就够，避免卡片信息过载。
 */
export function formatDate(value) {
  if (!value) return ''
  const text = String(value)
  const datePart = text.split(' ')[0]
  return datePart || text
}

/**
 * 相对时间（详情页用，比绝对时间更有"人味"）。
 * 只做粗略分级，够用即可，不引第三方库。
 */
export function formatRelativeTime(value) {
  if (!value) return ''
  // "yyyy-MM-dd HH:mm:ss" 在部分浏览器（Safari）里 new Date 会失败，
  // 所以先把空格换成 T、把 '-' 换成 '/' 更保险。
  const normalized = String(value).replace(/-/g, '/')
  const time = new Date(normalized).getTime()
  if (!Number.isFinite(time)) return formatDate(value)

  const diff = Date.now() - time
  if (diff < 0) return '刚刚'
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour

  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  return formatDate(value)
}

/**
 * 商品图片地址。
 *
 * 后端返回的是相对路径（/static/uploads/xxx.jpg），Vite 已把 /static 代理到 8080，
 * 所以这里原样返回即可 —— 保持相对路径还有个好处：换域名/换端口时前端代码不用改。
 *
 * 注意：当前后端 uploads 目录是空的（seed 的 demo*.jpg 并不存在），
 * 因此实际会走 <img @error> 兜底占位图，这是预期行为。
 */
export function resolveImageUrl(path) {
  if (!path) return ''
  if (/^(https?:)?\/\//.test(path) || path.startsWith('data:')) return path
  return path.startsWith('/') ? path : `/${path}`
}

/** 去掉空值，避免把 undefined / '' 也拼进 query（后端会把空串当有效参数校验） */
export function pruneEmpty(obj) {
  const result = {}
  Object.keys(obj || {}).forEach((key) => {
    const value = obj[key]
    if (value === undefined || value === null || value === '') return
    result[key] = value
  })
  return result
}

/** 昵称兜底：没昵称就用用户名，都没有就给「同学」 */
export function displayName(user) {
  if (!user) return '同学'
  return user.nickname || user.username || '同学'
}

/** 头像兜底：用昵称首字生成文字头像（不依赖任何图片资源） */
export function avatarText(user) {
  const name = displayName(user)
  return name.trim().charAt(0).toUpperCase() || '同'
}
