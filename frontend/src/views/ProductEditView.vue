<script setup>
/**
 * 编辑商品 /product/edit/:id
 *
 * 与发布页共用 ProductForm，差别只有两点：进入前要校验归属、提交后按状态规则给不同提示。
 *
 * 权限：只有 sellerId === 当前用户 ID 才能进；否则跳 403 页。
 *   （后端 PUT 也会用 203 兜底，前端这层只是把体验做好，不是唯一防线。）
 *
 * 状态规则（与后端 ProductServiceImpl.resolveStatus / isCriticalChanged 严格对齐，已逐行核对）：
 *   · status=1 上架中：改**关键字段**（title / description / price / conditionLevel / imageUrls）→ 重置为 3-待审核；
 *                      只改**非关键字段**（tradeType / tradeLocation）→ 保持 1，直接生效
 *   · status=0 已下架 / 3 待审核 → 重置为 3
 *   · status=2 已售罄 → 新库存 > 0 则变为 1-上架中，否则保持 2
 * 前端把同一套规则算一遍，只为了给对提示文案；真正的状态以后端返回为准。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Check } from '@element-plus/icons-vue'
import ProductForm from '@/components/ProductForm.vue'
import { getProductDetail, updateProduct } from '@/api/product'
import { getProfile } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { CODE, productStatusLabel } from '@/utils/constants'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const productId = computed(() => String(route.params.id || ''))

const loading = ref(true)
const submitting = ref(false)
/** 原始商品详情，用于判断关键字段是否变更 */
const original = ref(null)
const formRef = ref()

const currentStatus = computed(() => Number(original.value?.status))

/** 顶部规则说明：让用户提交前就知道会发生什么 */
const ruleTip = computed(() => {
  switch (currentStatus.value) {
    case 1:
      return '当前是「上架中」：改标题 / 描述 / 价格 / 成色 / 图片会重新进入待审核；只改交易方式或面交地点则直接生效。'
    case 2:
      return '当前是「已售罄」：把库存改回大于 0，保存后会直接恢复上架。'
    case 3:
      return '当前是「待审核」：保存后会重新提交审核。'
    default:
      return '当前是「已下架」：保存后会重新提交管理员审核，审核通过才会上架。'
  }
})

// ------------------------------------------------------------------ 关键字段判定（与后端同规则）
function isCriticalChanged(before, after) {
  if (!before || !after) return false
  const norm = (v) => (v == null ? '' : String(v).trim())
  if (norm(before.title) !== norm(after.title)) return true
  if (norm(before.description) !== norm(after.description)) return true
  if (Number(before.price) !== Number(after.price)) return true
  if (Number(before.conditionLevel) !== Number(after.conditionLevel)) return true

  const oldImages = Array.isArray(before.imageUrls) ? before.imageUrls : []
  const newImages = Array.isArray(after.imageUrls) ? after.imageUrls : []
  if (oldImages.length !== newImages.length) return true
  return oldImages.some((url, i) => url !== newImages[i])
}

/** 预测保存后的状态，仅用于提示文案 */
function predictNextStatus(payload) {
  const current = currentStatus.value
  const stock = Number(payload.stock)
  if (current === 2) return stock > 0 ? 1 : 2
  if (current === 0 || current === 3) return 3
  return isCriticalChanged(original.value, payload) ? 3 : 1
}

// ------------------------------------------------------------------ 加载与权限
async function loadProduct() {
  loading.value = true
  try {
    // store 里的 userInfo 可能是老会话残留（没有 userId），先补一次个人资料
    if (!userStore.userInfo?.userId) {
      const profile = await getProfile().catch(() => null)
      if (profile) userStore.patchUserInfo(profile)
    }

    const detail = await getProductDetail(productId.value, { silent: true })
    original.value = detail

    // 归属校验：不是本人发布的商品直接去 403
    const mine = String(detail?.sellerId) === String(userStore.userInfo?.userId)
    if (!mine) {
      router.replace({ name: 'forbidden' })
      return
    }

    formRef.value?.setValues(detail)
  } catch (error) {
    console.warn('[product-edit] 加载失败：', error?.message)
    ElMessage.error('商品不存在或无权查看')
    router.replace({ name: 'product-my' })
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------ 提交
function handleEditError(error) {
  switch (error?.code) {
    case CODE.PARAM_ERROR:
      ElMessage.error(error.message || '参数校验未通过，请检查表单')
      break
    case CODE.NO_PERMISSION:
      ElMessage.error('无权编辑该商品')
      router.replace({ name: 'forbidden' })
      break
    case CODE.PRODUCT_NOT_AVAILABLE:
      ElMessage.error('商品不存在或已被删除')
      router.replace({ name: 'product-my' })
      break
    default:
      ElMessage.error(error?.message || '保存失败，请稍后重试')
  }
}

async function handleSubmit() {
  // ① 同步锁：必须在任何 await 之前（与发布页/下单页同一模式）
  if (submitting.value) return
  submitting.value = true

  try {
    const valid = await formRef.value?.validate()
    if (!valid) return

    const payload = formRef.value.getPayload()
    const predicted = predictNextStatus(payload)

    await updateProduct(productId.value, payload, { silent: true })

    // ② 按预测状态给不同提示
    if (predicted === 3) {
      ElMessage.success('修改已提交，需重新审核')
    } else {
      ElMessage.success('修改成功')
    }
    router.replace({ name: 'product-my' })
  } catch (error) {
    handleEditError(error)
  } finally {
    submitting.value = false
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push({ name: 'product-my' })
}

onMounted(loadProduct)
</script>

<template>
  <main class="edit cm-container">
    <div class="edit__crumb">
      <el-button link :icon="ArrowLeft" class="edit__back" @click="goBack">返回</el-button>
      <span class="edit__crumb-sep">/</span>
      <span class="edit__crumb-text">编辑商品</span>
    </div>

    <header class="edit__header">
      <h1 class="edit__title">编辑商品</h1>
      <p v-if="original" class="edit__sub">
        当前状态：<b>{{ productStatusLabel(original.status) }}</b>
      </p>
    </header>

    <!-- 加载态 -->
    <div v-if="loading" class="edit__skeleton">
      <el-skeleton :rows="8" animated />
    </div>

    <template v-else-if="original">
      <!-- 状态规则说明：提交前就把后果说清楚 -->
      <el-alert class="edit__rule" type="info" show-icon :closable="false" title="保存后的状态变化" :description="ruleTip" />

      <ProductForm ref="formRef" :initial="original" :submitting="submitting" />

      <div class="edit__bar">
        <div class="edit__tips">提交后将按上面的规则更新状态</div>
        <el-button
          class="edit__submit"
          size="large"
          :icon="Check"
          :loading="submitting"
          @click="handleSubmit"
        >
          保存修改
        </el-button>
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
.edit {
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
    margin-bottom: 16px;
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

    b {
      color: $cm-primary-700;
    }
  }

  &__skeleton {
    @include cm-card;
    padding: 24px;
  }

  &__rule {
    margin-bottom: 16px;
    border-radius: $cm-radius;

    :deep(.el-alert__description) {
      font-size: 12px;
      line-height: 1.7;
    }
  }

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
    font-size: 12px;
    color: $cm-text-secondary;
  }

  // 主按钮走品牌绿（保存不是「交易动作」，所以不用橙金渐变）
  &__submit {
    height: 46px;
    min-width: 160px;
    padding: 0 24px;
    border-radius: $cm-radius;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: 2px;
  }

  @include cm-max($cm-bp-sm) {
    &__bar {
      flex-direction: column;
      align-items: stretch;

      :deep(.el-button) {
        width: 100%;
      }
    }
  }
}
</style>
