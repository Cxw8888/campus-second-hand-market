/**
 * 文件上传接口（对应后端 UploadController，前缀 /api/v1/upload）
 */
import request from '@/utils/request'

/**
 * 上传单张商品图片（强制认证，multipart/form-data，字段名必须是 file）
 *
 * 两个容易踩的点：
 *   1. **不要手动设置 Content-Type**。axios v1 在浏览器环境下发现 body 是 FormData 时，
 *      会主动把 Content-Type 删掉，交给浏览器自动带上 boundary；
 *      如果这里手写 'multipart/form-data'（没有 boundary），后端会解析失败。
 *      本项目的 axios 实例默认头是 application/json，同样会被 axios 自动移除，所以不用管。
 *   2. 上传超时单独放大到 30s：图片比普通 JSON 请求慢得多，用全局 10s 容易误报超时。
 *
 * @param {File} file 已经过 canvas 压缩的文件（见 utils/image.js）
 * @param {object} [options]
 * @param {(percent:number)=>void} [options.onProgress] 上传进度回调（0-100）
 * @returns {Promise<{ url: string }>} url 形如 /static/uploads/product/{userId}/xxx.jpg
 */
export function uploadImage(file, { onProgress } = {}) {
  const formData = new FormData()
  formData.append('file', file)

  return request.post('/upload/image', formData, {
    timeout: 30000,
    onUploadProgress(event) {
      if (!onProgress) return
      const total = event.total || 0
      if (!total) return
      onProgress(Math.min(100, Math.round((event.loaded * 100) / total)))
    }
  })
}
