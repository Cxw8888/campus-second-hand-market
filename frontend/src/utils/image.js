/**
 * 图片压缩（canvas 实现）
 *
 * 目标：把手机拍的原图（常见 3~8MB、4000px 级别）压到「最大边长 1920px + 质量 0.8」，
 * 通常能降到 300~500KB，既省流量也避免撞后端限制。
 *
 * 后端 UploadController 的约束（实现压缩参数时特意留了余量）：
 *   ≤ 5MB、白名单 jpg/jpeg/png/webp（魔数校验）、最大边长 8192px、总像素 ≤ 5000 万。
 *
 * 两个刻意的设计：
 *   1. 已经足够小的图**不重编码** —— 重编码只会掉画质，不会更小；
 *   2. 输出统一为 JPEG，但重编码前先铺一层白底，
 *      否则带透明通道的 PNG 转 JPEG 后透明区域会变成黑块。
 */

/** 压缩后的最大边长（px） */
export const MAX_EDGE = 1920

/** JPEG 输出质量 */
export const JPEG_QUALITY = 0.8

/** 小于这个体积且边长不超标时，直接原图上传，不做无谓重编码 */
export const COMPRESS_THRESHOLD_BYTES = 500 * 1024

/** 后端对单个文件的硬上限（超过会被 100/i/o 错误拒绝），这里用来说人话提示 */
export const BACKEND_MAX_BYTES = 5 * 1024 * 1024

/** 是否是图片类型 */
export function isImageFile(file) {
  return Boolean(file) && /^image\//.test(file.type || '')
}

/** 可读的体积文案 */
export function formatBytes(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n <= 0) return '0 KB'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}

/**
 * 用 <img> 解码（比 createImageBitmap 兼容性更好，Safari 老版本也认）
 * @returns {Promise<{img: HTMLImageElement, revoke: Function}>}
 */
function loadImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => resolve({ img, revoke: () => URL.revokeObjectURL(url) })
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('图片解码失败，请换一张图片试试'))
    }
    img.src = url
  })
}

/** canvas.toBlob 的 Promise 包装 */
function toBlob(canvas, type, quality) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('图片压缩失败'))),
      type,
      quality
    )
  })
}

/**
 * 压缩单张图片
 *
 * @param {File} file 原始文件
 * @param {object} [options]
 * @param {number} [options.maxEdge=1920] 最大边长
 * @param {number} [options.quality=0.8]   JPEG 质量
 * @returns {Promise<File>} 压缩后的 File（无需压缩时原样返回）
 */
export async function compressImage(file, options = {}) {
  const { maxEdge = MAX_EDGE, quality = JPEG_QUALITY, threshold = COMPRESS_THRESHOLD_BYTES } = options

  if (!isImageFile(file)) return file

  const { img, revoke } = await loadImage(file)
  try {
    const width = img.naturalWidth || img.width
    const height = img.naturalHeight || img.height
    if (!width || !height) return file

    const longest = Math.max(width, height)
    const needResize = longest > maxEdge

    // 已经够小：不重编码，保留原始格式与画质
    if (!needResize && file.size <= threshold) {
      return file
    }

    const scale = needResize ? maxEdge / longest : 1
    const targetW = Math.max(1, Math.round(width * scale))
    const targetH = Math.max(1, Math.round(height * scale))

    const canvas = document.createElement('canvas')
    canvas.width = targetW
    canvas.height = targetH

    const ctx = canvas.getContext('2d')
    // 铺白底：避免 PNG 透明区域转 JPEG 后变黑
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, targetW, targetH)
    ctx.drawImage(img, 0, 0, targetW, targetH)

    const blob = await toBlob(canvas, 'image/jpeg', quality)

    // 压缩后反而更大（例如极小的 PNG）→ 用原图
    if (blob.size >= file.size && !needResize) return file

    const baseName = String(file.name || 'image').replace(/\.[^.]+$/, '')
    return new File([blob], `${baseName}.jpg`, {
      type: 'image/jpeg',
      lastModified: Date.now()
    })
  } finally {
    revoke()
  }
}
