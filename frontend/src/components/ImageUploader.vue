<script setup>
/**
 * 商品图片上传组件
 *
 * 用 el-upload 的 `http-request` 自己实现上传（而不是走它的默认 XHR），因为要在上传前插入
 * 一步「canvas 压缩」：手机原图动辄 3~8MB，直接传既慢又容易撞后端 5MB 上限。
 *
 * 交互设计：
 *   · 网格展示已上传的图，第 1 张标「封面」（后端取 imageUrls[0] 作为列表封面）
 *   · 每个图 hover 出现「预览 / 删除」
 *   · 上传中显示圆形进度条；上传期间禁止继续添加，避免多文件并发共享进度条
 *   · 超限（>9 张）时「添加」按钮直接隐藏
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, ZoomIn, Delete } from '@element-plus/icons-vue'
import ProductImage from '@/components/ProductImage.vue'
import { uploadImage } from '@/api/upload'
import { BACKEND_MAX_BYTES, compressImage, formatBytes, isImageFile } from '@/utils/image'
import { MAX_PRODUCT_IMAGES } from '@/utils/constants'

const props = defineProps({
  /** 已上传的图片 URL 列表（v-model） */
  modelValue: { type: Array, default: () => [] },
  /** 上限，默认 9（与后端 @Size(max=9) 对齐） */
  max: { type: Number, default: MAX_PRODUCT_IMAGES }
})

const emit = defineEmits(['update:modelValue'])

const uploading = ref(false)
const progress = ref(0)
/** 预览弹窗 */
const viewerVisible = ref(false)
const viewerIndex = ref(0)

const images = computed(() => props.modelValue || [])
const canAdd = computed(() => !uploading.value && images.value.length < props.max)
const currentImage = computed(() => images.value[viewerIndex.value] || '')

/**
 * 自定义上传：压缩 → 上传 → 写入 v-model
 * el-upload 会把选中的文件放在 options.file 里
 */
async function handleUpload(options) {
  const file = options.file

  if (!isImageFile(file)) {
    ElMessage.error('只能上传图片文件（jpg / png / webp）')
    options.onError?.(new Error('不是图片'))
    return
  }
  if (images.value.length >= props.max) {
    ElMessage.warning(`最多上传 ${props.max} 张图片`)
    options.onError?.(new Error('超出数量上限'))
    return
  }

  uploading.value = true
  progress.value = 0
  try {
    // ① canvas 压缩（最大边长 1920、质量 0.8；已经够小的图不重编码）
    const compressed = await compressImage(file)

    if (compressed.size > BACKEND_MAX_BYTES) {
      ElMessage.error(`图片压缩后仍有 ${formatBytes(compressed.size)}，超过后端 5MB 上限，请换一张`)
      options.onError?.(new Error('文件过大'))
      return
    }

    // ② 上传
    const data = await uploadImage(compressed, {
      onProgress: (percent) => {
        progress.value = percent
      }
    })

    if (!data?.url) throw new Error('上传接口未返回图片地址')

    emit('update:modelValue', [...images.value, data.url])
    options.onSuccess?.(data)

    // 把压缩效果告诉用户，答辩演示时能直观看到「压缩确实生效」
    ElMessage.success(
      compressed === file
        ? '上传成功'
        : `上传成功（${formatBytes(file.size)} → ${formatBytes(compressed.size)}）`
    )
  } catch (error) {
    console.warn('[uploader] 上传失败：', error?.message)
    options.onError?.(error)
    // 业务错误（100/401 等）已由 axios 拦截器提示过，这里只兜底「没有提示过」的情况
    if (!error?.code) ElMessage.error(error?.message || '图片上传失败，请重试')
  } finally {
    uploading.value = false
    progress.value = 0
  }
}

function removeImage(index) {
  const next = [...images.value]
  next.splice(index, 1)
  emit('update:modelValue', next)
}

function openViewer(index) {
  viewerIndex.value = index
  viewerVisible.value = true
}
</script>

<template>
  <div class="image-uploader">
    <div class="image-uploader__grid">
      <!-- 已上传的图片 -->
      <div v-for="(url, index) in images" :key="url + index" class="image-uploader__item">
        <ProductImage :src="url" :alt="`商品图 ${index + 1}`" ratio="1 / 1" :icon-size="22" />

        <span v-if="index === 0" class="image-uploader__cover-badge">封面</span>

        <div class="image-uploader__mask">
          <el-icon class="image-uploader__action" :size="18" title="预览" @click="openViewer(index)">
            <ZoomIn />
          </el-icon>
          <el-icon class="image-uploader__action" :size="18" title="删除" @click="removeImage(index)">
            <Delete />
          </el-icon>
        </div>
      </div>

      <!-- 上传中 -->
      <div v-if="uploading" class="image-uploader__item image-uploader__item--uploading">
        <el-progress type="circle" :percentage="progress" :width="52" :stroke-width="5" />
        <span class="image-uploader__uploading-text">压缩上传中</span>
      </div>

      <!-- 添加按钮（用 el-upload 包一层，点击即选文件） -->
      <el-upload
        v-if="canAdd"
        class="image-uploader__add"
        :show-file-list="false"
        :multiple="false"
        accept="image/jpeg,image/png,image/webp"
        :http-request="handleUpload"
      >
        <div class="image-uploader__add-inner">
          <el-icon :size="20"><Plus /></el-icon>
          <span>添加图片</span>
        </div>
      </el-upload>
    </div>

    <p class="image-uploader__tip">
      最多 {{ max }} 张，<b>第 1 张作为封面</b>；上传前会自动压缩（最大边长 1920px、质量 0.8），
      支持 jpg / png / webp。
      <span v-if="images.length" class="image-uploader__count">
        已上传 {{ images.length }} / {{ max }} 张
      </span>
    </p>

    <!-- 预览弹窗 -->
    <el-dialog v-model="viewerVisible" width="640px" align-center :title="`图片预览（第 ${viewerIndex + 1} 张）`">
      <img v-if="currentImage" class="image-uploader__preview" :src="currentImage" alt="商品图预览" />
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.image-uploader {
  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
    gap: 12px;
  }

  &__item {
    position: relative;
    border-radius: $cm-radius;
    overflow: hidden;
    border: 1px solid $cm-border;
    aspect-ratio: 1 / 1;

    &--uploading {
      @include cm-center;
      flex-direction: column;
      gap: 8px;
      background: $cm-bg;
      border-style: dashed;
    }
  }

  &__uploading-text {
    font-size: 11px;
    color: $cm-text-secondary;
  }

  &__cover-badge {
    position: absolute;
    left: 0;
    top: 0;
    padding: 2px 8px;
    border-bottom-right-radius: $cm-radius;
    font-size: 11px;
    font-weight: 600;
    color: #ffffff;
    background: $cm-primary;
  }

  // hover 才出现的操作蒙层
  &__mask {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 16px;
    background: rgba(17, 24, 39, 0.52);
    opacity: 0;
    transition: opacity 0.2s ease;
  }

  &__item:hover &__mask {
    opacity: 1;
  }

  &__action {
    color: #ffffff;
    cursor: pointer;
    transition: transform 0.16s ease;

    &:hover {
      transform: scale(1.15);
    }
  }

  // ---------------- 添加按钮 ----------------
  &__add {
    :deep(.el-upload) {
      display: block;
      width: 100%;
      height: 100%;
    }
  }

  &__add-inner {
    @include cm-center;
    flex-direction: column;
    gap: 6px;
    aspect-ratio: 1 / 1;
    border: 1px dashed $cm-border;
    border-radius: $cm-radius;
    background: $cm-bg;
    color: $cm-text-secondary;
    font-size: 12px;
    cursor: pointer;
    transition:
      border-color 0.2s ease,
      color 0.2s ease,
      background 0.2s ease;

    &:hover {
      border-color: $cm-primary;
      color: $cm-primary;
      background: $cm-primary-50;
    }
  }

  &__tip {
    margin-top: 10px;
    font-size: 12px;
    color: $cm-text-placeholder;
    line-height: 1.7;

    b {
      color: $cm-text-secondary;
    }
  }

  &__count {
    margin-left: 8px;
    color: $cm-primary-700;
  }

  &__preview {
    width: 100%;
    border-radius: $cm-radius;
  }
}
</style>
