/**
 * ProductImage：缩略图 → 原图 → 占位图 的三级降级（批次 6.0.5.2 · M6-A4）
 *
 * 背景：列表页改用 400px 缩略图（thumbUrl）后，有两类 URL 拿不到缩略图：
 *   ① 演示/seed 数据（demo-*.png，本来就没有 _thumb 副本）；
 *   ② 上传时缩略图生成失败的文件（LocalStorageImpl.writeThumbnail 失败只记 warn，不影响主图落盘）。
 * 若不处理，列表页会从"有图"退化成"暂无图片"占位图 —— 比不改还糟。
 * 因此 ProductImage 需要支持 fallbackSrc：缩略图加载失败时退回原图，原图也失败才显示占位图。
 *
 * ⚠️ jsdom 不会真的加载图片，因此这里用 trigger('error') 手动触发 img 的 error 事件。
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ProductImage from '@/components/ProductImage.vue'

function mountImage(props) {
  return mount(ProductImage, {
    props,
    global: { stubs: { 'el-icon': true } }
  })
}

const imgOf = (wrapper) => wrapper.find('img')
const hasPlaceholder = (wrapper) => wrapper.find('.cm-image__placeholder').exists()

describe('ProductImage 缩略图与原图的降级链', () => {
  it('① 有缩略图 → 优先使用缩略图地址', () => {
    const wrapper = mountImage({
      src: '/static/uploads/product/7/a_thumb.jpg',
      fallbackSrc: '/static/uploads/product/7/a.jpg'
    })

    expect(imgOf(wrapper).attributes('src')).toBe('/static/uploads/product/7/a_thumb.jpg')
    expect(hasPlaceholder(wrapper)).toBe(false)
  })

  it('② 缩略图 404 → 退回原图（而不是直接显示占位图）', async () => {
    const wrapper = mountImage({
      src: '/static/uploads/product/7/a_thumb.jpg',
      fallbackSrc: '/static/uploads/product/7/a.jpg'
    })

    await imgOf(wrapper).trigger('error')

    expect(imgOf(wrapper).attributes('src')).toBe('/static/uploads/product/7/a.jpg')
    expect(hasPlaceholder(wrapper)).toBe(false)
  })

  it('③ 原图也失败 → 显示占位图（且不会死循环：只降级一次）', async () => {
    const wrapper = mountImage({
      src: '/static/uploads/product/7/a_thumb.jpg',
      fallbackSrc: '/static/uploads/product/7/a.jpg'
    })

    await imgOf(wrapper).trigger('error') // 缩略图失败 → 切原图
    await imgOf(wrapper).trigger('error') // 原图也失败 → 占位图

    expect(hasPlaceholder(wrapper)).toBe(true)
  })

  it('④ 没有缩略图（thumbUrl 为 null 时前端传原图、无 fallback）→ 直接用原图', () => {
    const wrapper = mountImage({ src: '/static/uploads/demo-textbook-01.png' })

    expect(imgOf(wrapper).attributes('src')).toBe('/static/uploads/demo-textbook-01.png')
    expect(hasPlaceholder(wrapper)).toBe(false)
  })

  it('⑤ 空地址 → 直接显示占位图（既有行为回归）', () => {
    const wrapper = mountImage({ src: '' })

    expect(imgOf(wrapper).exists()).toBe(false)
    expect(hasPlaceholder(wrapper)).toBe(true)
  })
})
