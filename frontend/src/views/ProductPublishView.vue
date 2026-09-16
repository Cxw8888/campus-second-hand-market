<script setup>
/**
 * 发布商品 /product/publish
 *
 * 防连点（与下单页同一套修复模式，也是本批次测试覆盖的点）：
 *   `submitting.value = true` 必须在**任何 await 之前**同步置位。
 *   如果放在 `await formRef.validate()` 后面，快速连点会在任何一次置位之前全部穿过入口守卫，
 *   于是同一次点击风暴会发出多个发布请求（下单页当初就是这么踩的坑）。
 *
 * 提交成功后跳「发布成功页」，并把新商品 id 带过去（后端 Long→String，全程字符串）。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Upload } from '@element-plus/icons-vue'
import ProductForm from '@/components/ProductForm.vue'
import { createProduct } from '@/api/product'
import { CODE } from '@/utils/constants'

const router = useRouter()

const formRef = ref()
const submitting = ref(false)

/** 针对发布可能出现的错误码给出更贴场景的提示 */
function handlePublishError(error) {
  switch (error?.code) {
    case CODE.PARAM_ERROR:
      ElMessage.error(error.message || '参数校验未通过，请检查表单（标题 1-100 字、价格 > 0、至少 1 张图片）')
      break
    case CODE.UNAUTHORIZED:
      // 拦截器已经跳登录页了
      break
    case CODE.NO_PERMISSION:
      ElMessage.error('无权发布商品，请确认登录状态')
      break
    default:
      ElMessage.error(error?.message || '发布失败，请稍后重试')
  }
}

async function handleSubmit() {
  // ① 同步上锁：必须在任何 await 之前
  if (submitting.value) return
  submitting.value = true

  try {
    // ② 表单校验（标题长度、价格 > 0、图片非空、面交地点联动校验都在 ProductForm 里）
    const valid = await formRef.value?.validate()
    if (!valid) return

    // ③ 提交（后端落库 status=3 待审核）
    const data = await createProduct(formRef.value.getPayload(), { silent: true })

    ElMessage.success('发布成功，等待管理员审核')
    // replace：避免用户点返回又回到发布页重复提交
    router.replace({
      name: 'product-publish-success',
      params: { productId: String(data?.id ?? '') }
    })
  } catch (error) {
    handlePublishError(error)
  } finally {
    // ④ 请求返回（或校验失败）后才解锁
    submitting.value = false
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push({ name: 'home' })
}
</script>

<template>
  <main class="publish cm-container">
    <div class="publish__crumb">
      <el-button link :icon="ArrowLeft" class="publish__back" @click="goBack">返回</el-button>
      <span class="publish__crumb-sep">/</span>
      <span class="publish__crumb-text">发布商品</span>
    </div>

    <header class="publish__header">
      <h1 class="publish__title">发布闲置</h1>
      <p class="publish__sub">信息填得越清楚，越容易遇到合适的买家。发布后需管理员审核，通过后会在首页展示。</p>
    </header>

    <ProductForm ref="formRef" :submitting="submitting" />

    <!-- 底部提交栏 -->
    <div class="publish__bar">
      <div class="publish__tips">
        <span>发布后状态为「待审核」</span>
        <span class="publish__tips-dot" />
        <span>审核通过后自动上架</span>
      </div>

      <el-button
        class="publish__submit"
        size="large"
        :icon="Upload"
        :loading="submitting"
        @click="handleSubmit"
      >
        发布商品
      </el-button>
    </div>
  </main>
</template>

<style scoped lang="scss">
.publish {
  flex: 1;
  padding-top: 16px;
  padding-bottom: 40px;
  max-width: 880px;

  &__crumb {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
    font-size: 13px;
    color: $cm-text-secondary;
  }

  &__back {
    color: $cm-text-secondary;

    &:hover {
      color: $cm-primary;
    }
  }

  &__crumb-sep {
    color: $cm-text-placeholder;
  }

  &__header {
    margin-bottom: 18px;
  }

  &__title {
    font-size: 22px;
    font-weight: 800;
    color: $cm-text;
    margin-bottom: 6px;
  }

  &__sub {
    font-size: 13px;
    color: $cm-text-secondary;
    line-height: 1.7;
  }

  // ---------------- 底部提交栏 ----------------
  &__bar {
    @include cm-card;
    position: sticky;
    bottom: 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 14px 18px;
    margin-top: 16px;
    box-shadow: 0 6px 24px rgba(17, 24, 39, 0.12);
  }

  &__tips {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__tips-dot {
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: $cm-border;
  }

  // 与「立即购买 / 提交订单」同一套橙→金渐变
  &__submit {
    height: 46px;
    min-width: 168px;
    padding: 0 26px;
    border: none;
    border-radius: $cm-radius;
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 2px;
    color: #ffffff;
    background: $cm-gradient-buy;
    box-shadow: $cm-shadow-buy;
    transition:
      transform 0.2s ease,
      box-shadow 0.2s ease;

    &:hover:not(.is-disabled) {
      transform: translateY(-2px);
      box-shadow: 0 6px 18px rgba(245, 158, 11, 0.42);
    }
  }

  @include cm-max($cm-bp-sm) {
    &__bar {
      flex-direction: column;
      align-items: stretch;

      :deep(.el-button) {
        width: 100%;
      }
    }

    &__tips {
      justify-content: center;
    }
  }
}
</style>
