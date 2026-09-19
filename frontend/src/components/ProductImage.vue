<script setup>
/**
 * 商品图片（带占位兜底 + 一次降级）
 *
 * 为什么需要这个组件：
 *   后端 uploads 目录当前是空的 —— seed 数据里的 /static/uploads/demo*.jpg 并不存在，
 *   所有商品图都会 404。若直接写 <img>，列表页会是一堆浏览器的「碎图」图标，论文截图很难看。
 *   这里统一降级为「浅绿渐变 + 图标」的占位图，观感上像是刻意设计的空态。
 *
 * 降级链（批次 6.0.5.2 · M6-A4 起）：src 失败 → fallbackSrc（一次）→ 占位图。
 *   列表页用 400px 缩略图（thumbUrl）省流量，但演示数据与"缩略图生成失败"的文件没有缩略图，
 *   必须能退回原图，否则列表会从「有图」退化成「暂无图片」。
 *
 * 另外 @error 只触发一次（用 failed 标记挡住），避免死循环。
 */
import { computed, ref, watch } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import { resolveImageUrl } from '@/utils/format'

const props = defineProps({
  /** 图片地址（相对路径 /static/... 会被 Vite 代理到后端） */
  src: { type: String, default: '' },
  /** 无障碍描述 */
  alt: { type: String, default: '商品图片' },
  /** 宽高比，如 '4 / 3'、'1 / 1' */
  ratio: { type: String, default: '4 / 3' },
  /** 占位图标尺寸 */
  iconSize: { type: Number, default: 34 },
  /**
   * 一次性降级地址：src 加载失败时改用它（通常是原图）。
   * 为空表示不降级，直接显示占位图。
   */
  fallbackSrc: { type: String, default: '' }
})

/** src 是否已加载失败（失败后切到 fallbackSrc） */
const srcFailed = ref(false)

/** fallbackSrc 是否也失败（此时才落到占位图） */
const fallbackFailed = ref(false)

const resolved = computed(() => resolveImageUrl(props.src))
const resolvedFallback = computed(() => resolveImageUrl(props.fallbackSrc))

/** 当前实际使用的地址：src → fallbackSrc → 空（空则渲染占位图） */
const currentSrc = computed(() => {
  if (!srcFailed.value) return resolved.value
  if (!fallbackFailed.value) return resolvedFallback.value
  return ''
})

/** src / fallbackSrc 变化时重置降级状态，否则复用组件时会一直显示占位图 */
watch(
  () => [props.src, props.fallbackSrc],
  () => {
    srcFailed.value = false
    fallbackFailed.value = false
  }
)

const showPlaceholder = computed(() => !currentSrc.value)

/** 图片加载失败：第一次切 fallbackSrc，第二次才落到占位图（最多两次，不会死循环） */
function handleError() {
  if (!srcFailed.value) {
    srcFailed.value = true
    return
  }
  fallbackFailed.value = true
}
</script>

<template>
  <div class="cm-image" :style="{ aspectRatio: ratio }">
    <img
      v-if="!showPlaceholder"
      class="cm-image__img"
      :src="currentSrc"
      :alt="alt"
      loading="lazy"
      @error="handleError"
    />
    <div v-else class="cm-image__placeholder">
      <el-icon :size="iconSize"><Picture /></el-icon>
      <span class="cm-image__hint">暂无图片</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.cm-image {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: $cm-border-light;

  &__img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform 0.3s ease;
  }

  &__placeholder {
    @include cm-center;
    flex-direction: column;
    gap: 6px;
    width: 100%;
    height: 100%;
    // 浅绿→浅灰的柔和渐变，比纯灰更有「校园感」，也不会抢商品卡片的视觉重心
    background: linear-gradient(135deg, $cm-primary-50 0%, #f0fdf4 50%, $cm-border-light 100%);
    color: $cm-primary;
    user-select: none;
  }

  &__hint {
    font-size: 12px;
    color: $cm-text-placeholder;
    letter-spacing: 0.5px;
  }
}
</style>
