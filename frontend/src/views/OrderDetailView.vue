<script setup>
/**
 * 订单详情 /order/detail/:orderId
 *
 * 页面上的一切都由「订单状态 + 当前用户是买家还是卖家」推导出来：
 *   待支付(0) 买家 → 去支付 / 取消订单
 *   待支付(0) 卖家 + 面交单 → 确认已完成（finish-face，后端用 seller_id 校验）
 *   已支付(1) 买家 → 申请退款
 *   已支付(1) 卖家 + 邮寄单 → 发货（面交单严禁发货，后端 SQL 也会拦成 209，所以这里直接不显示）
 *   已发货(2) 买家 → 确认收货
 *   退款申请中(6) 卖家 → 同意退款 / 拒绝退款
 *   退款被拒(7) 买家 → 只展示提示文案，等待管理员介入
 *
 * ⚠️ orderId / orderNo 全程字符串，禁止 Number 转换（雪花 Long 精度问题）。
 * ⚠️ 卖家信息取自商品详情接口（OrderVO 不含卖家昵称），商品被删除时优雅降级 —— 且只展示昵称与地点，
 *    绝无手机号/邮箱（后端 VO 本身也不下发这些敏感字段）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Location, Van, Clock, ChatDotRound } from '@element-plus/icons-vue'
import ProductImage from '@/components/ProductImage.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import PayCountdown from '@/components/PayCountdown.vue'
import OrderTimeline from '@/components/OrderTimeline.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { getProductDetail } from '@/api/product'
import {
  agreeRefund,
  applyRefund,
  cancelOrder,
  finishFaceOrder,
  getOrderDetail,
  payOrder,
  receiveOrder,
  rejectRefund,
  shipOrder
} from '@/api/order'
import { useUserStore } from '@/stores/user'
import { formatPrice, formatDate } from '@/utils/format'
import { orderStatusHint } from '@/utils/constants'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const orderId = computed(() => String(route.params.orderId || ''))

const loading = ref(true)
const acting = ref(false)
const order = ref(null)
/** 商品详情（用于取卖家昵称/交易地点/封面），商品已删除时为 null */
const productInfo = ref(null)

const status = computed(() => Number(order.value?.status))
const tradeType = computed(() => Number(order.value?.tradeType))
const isFace = computed(() => tradeType.value === 1)
const isBuyer = computed(
  () => String(order.value?.userId) === String(userStore.userInfo?.userId)
)
const isSeller = computed(
  () => String(order.value?.sellerId) === String(userStore.userInfo?.userId)
)
const isPending = computed(() => status.value === 0)

const amount = computed(() => formatPrice(order.value?.amount))
const unitPrice = computed(() => formatPrice(order.value?.productPrice))
const hint = computed(() => orderStatusHint(status.value))

/** 交易信息行：面交显示约定地点，邮寄显示收货地址（都来自订单的 address 字段） */
const placeLabel = computed(() => (isFace.value ? '面交地点' : '收货地址'))
const placeValue = computed(() => order.value?.address || productInfo.value?.tradeLocation || '未填写')

const sellerName = computed(() => productInfo.value?.sellerNickname || '')
const sellerInitial = computed(() => (sellerName.value || '同').trim().charAt(0))
const sellerPlace = computed(() => productInfo.value?.tradeLocation || placeValue.value)
/** 商品已删除 / 取不到时，卖家卡片降级展示 */
const sellerUnavailable = computed(() => !productInfo.value)

// ------------------------------------------------------------------ 可执行操作
const canPay = computed(() => isPending.value && isBuyer.value)
const canCancel = computed(() => isPending.value && isBuyer.value)
const canFinishFace = computed(() => isPending.value && isSeller.value && isFace.value)
const canApplyRefund = computed(() => status.value === 1 && isBuyer.value)
const canShip = computed(() => status.value === 1 && isSeller.value && !isFace.value)
const canReceive = computed(() => status.value === 2 && isBuyer.value)
const canHandleRefund = computed(() => status.value === 6 && isSeller.value)
/** 退款被拒：买家只能等管理员介入 */
const showRefundRejectedTip = computed(() => status.value === 7 && isBuyer.value)

const hasActions = computed(
  () =>
    canPay.value ||
    canCancel.value ||
    canFinishFace.value ||
    canApplyRefund.value ||
    canShip.value ||
    canReceive.value ||
    canHandleRefund.value
)

// ------------------------------------------------------------------ 数据加载
async function loadOrder() {
  try {
    order.value = await getOrderDetail(orderId.value, { silent: true })
  } catch (error) {
    console.warn('[order-detail] 订单加载失败：', error?.message)
    ElMessage.error('订单不存在或无权查看')
    order.value = null
  }
}

/** 卖家昵称、封面图、交易地点都在商品接口里；商品被删除会失败，此时静默降级 */
async function loadProduct() {
  if (!order.value?.productId) return
  try {
    productInfo.value = await getProductDetail(order.value.productId, { silent: true })
  } catch {
    productInfo.value = null
  }
}

async function reloadAll() {
  await loadOrder()
  await loadProduct()
}

// ------------------------------------------------------------------ 操作封装
/** 统一处理「确认弹窗 → 调接口 → 提示 → 刷新」这套流程，避免每个按钮重复写一遍 */
async function runAction({ confirm, request, successText }) {
  if (confirm) {
    try {
      await ElMessageBox.confirm(confirm.message, confirm.title, {
        confirmButtonText: confirm.confirmText || '确定',
        cancelButtonText: '取消',
        type: confirm.type || 'warning',
        customClass: 'cm-confirm-box'
      })
    } catch {
      return // 用户取消
    }
  }

  acting.value = true
  try {
    await request()
    ElMessage.success(successText)
    await reloadAll()
  } catch (error) {
    console.warn('[order-detail] 操作失败：', error?.message)
    // 状态冲突（209）通常意味着状态已被别处改掉，刷新一次让页面回到真实状态
    await reloadAll()
  } finally {
    acting.value = false
  }
}

function handlePay() {
  runAction({
    confirm: {
      message: `即将支付 ¥${amount.value}。\n本系统为模拟支付，真实资金请在线下完成。`,
      title: '确认支付',
      confirmText: '确认支付'
    },
    request: () => payOrder(orderId.value),
    successText: '支付成功，等待卖家发货'
  })
}

function handleCancel() {
  runAction({
    confirm: {
      message: '取消后库存会立即回补，该订单不可恢复。确定要取消这笔订单吗？',
      title: '取消订单',
      confirmText: '确认取消',
      type: 'warning'
    },
    request: () => cancelOrder(orderId.value),
    successText: '订单已取消'
  })
}

function handleFinishFace() {
  runAction({
    confirm: {
      message: '确认已经完成线下见面交易？确认后订单将直接变为「已完成」。',
      title: '确认面交完成',
      confirmText: '确认已完成'
    },
    request: () => finishFaceOrder(orderId.value),
    successText: '订单已完成'
  })
}

function handleShip() {
  runAction({
    confirm: {
      message: '确认已经寄出商品？确认后买家会看到「已发货待收货」。',
      title: '确认发货',
      confirmText: '确认发货'
    },
    request: () => shipOrder(orderId.value),
    successText: '发货成功'
  })
}

function handleReceive() {
  runAction({
    confirm: {
      message: '确认已经收到商品？确认后订单将变为「已完成」，且不可撤销。',
      title: '确认收货',
      confirmText: '确认收货'
    },
    request: () => receiveOrder(orderId.value),
    successText: '确认收货成功，交易完成'
  })
}

function handleAgreeRefund() {
  runAction({
    confirm: {
      message: '同意退款后订单将关闭，库存会自动回补。确定同意吗？',
      title: '同意退款',
      confirmText: '同意退款'
    },
    request: () => agreeRefund(orderId.value),
    successText: '已同意退款，订单已关闭'
  })
}

/** 申请退款 / 拒绝退款都需要填原因，用 prompt 收集 */
async function handleApplyRefund() {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请说明退款原因（最长 200 字），卖家会据此处理', '申请退款', {
      confirmButtonText: '提交申请',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '例如：收到后发现与描述不符，屏幕有划痕',
      inputValidator: (value) => {
        if (!value || !value.trim()) return '退款原因不能为空'
        if (value.length > 200) return '退款原因不能超过 200 字'
        return true
      }
    })
    reason = result.value.trim()
  } catch {
    return
  }

  await runAction({
    request: () => applyRefund(orderId.value, reason),
    successText: '退款申请已提交，等待卖家处理'
  })
}

async function handleRejectRefund() {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请说明拒绝退款的原因（最长 200 字）', '拒绝退款', {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '例如：商品描述已注明有使用痕迹，实物与描述一致',
      inputValidator: (value) => {
        if (!value || !value.trim()) return '拒绝原因不能为空'
        if (value.length > 200) return '拒绝原因不能超过 200 字'
        return true
      }
    })
    reason = result.value.trim()
  } catch {
    return
  }

  await runAction({
    confirm: {
      message: '拒绝后订单进入 3 天申诉期，期间买家可等待管理员介入。确定拒绝吗？',
      title: '确认拒绝退款',
      confirmText: '确认拒绝'
    },
    request: () => rejectRefund(orderId.value, reason),
    successText: '已拒绝退款'
  })
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push({ name: 'order-list' })
}

onMounted(reloadAll)
</script>

<template>
  <main class="order-detail cm-container">
    <div class="order-detail__crumb">
      <el-button link :icon="ArrowLeft" class="order-detail__back" @click="goBack">返回</el-button>
      <span class="order-detail__crumb-sep">/</span>
      <span class="order-detail__crumb-text">订单详情</span>
    </div>

    <div v-if="loading" class="order-detail__skeleton">
      <el-skeleton :rows="8" animated />
    </div>

    <template v-else-if="order">
      <!-- ---------------- 状态头部 ---------------- -->
      <section class="order-detail__head">
        <div class="order-detail__head-left">
          <OrderStatusTag :status="order.status" size="md" />
          <span class="order-detail__role-tag">{{ isSeller ? '我卖出的' : '我买到的' }}</span>
        </div>

        <div class="order-detail__head-right">
          <div v-if="isPending" class="order-detail__countdown">
            <el-icon :size="14"><Clock /></el-icon>
            <PayCountdown :create-time="order.createTime" @expire="reloadAll" />
          </div>
          <p v-else-if="hint" class="order-detail__hint">{{ hint }}</p>
        </div>

        <div class="order-detail__no">
          订单号 <b class="cm-num">{{ order.orderNo }}</b>
        </div>
      </section>

      <!-- 退款被拒：买家侧只提示等待管理员 -->
      <el-alert
        v-if="showRefundRejectedTip"
        class="order-detail__alert"
        type="warning"
        show-icon
        :closable="false"
        title="退款被拒，可等待管理员介入"
        :description="order.refundRejectReason ? `卖家拒绝原因：${order.refundRejectReason}` : ''"
      />

      <!-- ---------------- 商品快照 ---------------- -->
      <section class="order-detail__product">
        <h2 class="order-detail__section-title">商品信息</h2>
        <div class="order-detail__product-row">
          <div class="order-detail__cover">
            <ProductImage
              :src="order.productCover || productInfo?.coverImage"
              :alt="order.productTitle"
              ratio="1 / 1"
              :icon-size="24"
            />
          </div>
          <div class="order-detail__product-main">
            <h3 class="order-detail__product-title">{{ order.productTitle || '商品信息已不可见' }}</h3>
            <div class="order-detail__product-meta">
              <span>单价 ¥{{ unitPrice }}</span>
              <span>× {{ order.quantity }} 件</span>
              <span v-if="order.productDeleted" class="order-detail__deleted">商品已删除（展示订单快照）</span>
            </div>
          </div>
          <div class="order-detail__product-amount">
            <span class="cm-price">
              <span class="cm-price__symbol">¥</span>{{ amount }}
            </span>
            <span class="order-detail__amount-label">订单金额</span>
          </div>
        </div>
      </section>

      <!-- ---------------- 交易信息 ---------------- -->
      <section class="order-detail__info">
        <h2 class="order-detail__section-title">交易信息</h2>
        <dl class="order-detail__rows">
          <div class="order-detail__row">
            <dt>交易方式</dt>
            <!-- 交易方式三色标签：面交绿 / 邮寄蓝 / 皆可橙 -->
            <dd><TradeTypeTag :type="tradeType" size="sm" /></dd>
          </div>
          <div class="order-detail__row">
            <dt>
              <el-icon :size="13">
                <component :is="isFace ? Location : Van" />
              </el-icon>
              {{ placeLabel }}
            </dt>
            <dd class="order-detail__row-strong">{{ placeValue }}</dd>
          </div>
          <div class="order-detail__row">
            <dt>下单时间</dt>
            <dd>{{ formatDate(order.createTime) }}</dd>
          </div>
        </dl>
      </section>

      <!-- ---------------- 卖家信息 ---------------- -->
      <section class="order-detail__seller">
        <h2 class="order-detail__section-title">卖家信息</h2>

        <template v-if="!sellerUnavailable">
          <div class="order-detail__seller-row">
            <span class="order-detail__seller-avatar">{{ sellerInitial }}</span>
            <div class="order-detail__seller-main">
              <div class="order-detail__seller-name">{{ sellerName || '匿名同学' }}</div>
              <div class="order-detail__seller-place">
                <el-icon :size="12"><Location /></el-icon>
                {{ sellerPlace || '交易地点待约定' }}
              </div>
            </div>
            <span class="order-detail__seller-tip">
              <el-icon :size="12"><ChatDotRound /></el-icon>
              如需联系请在订单内留言约定时间
            </span>
          </div>
        </template>
        <p v-else class="order-detail__seller-fallback">
          商品已被删除，卖家信息不可见；订单快照仍然完整，可作为交易凭证。
        </p>
      </section>

      <!-- ---------------- 订单进度 ---------------- -->
      <OrderTimeline :order="order" />

      <!-- ---------------- 底部操作栏 ---------------- -->
      <div v-if="hasActions" class="order-detail__bar">
        <el-button v-if="canCancel" size="large" round plain :disabled="acting" @click="handleCancel">
          取消订单
        </el-button>

        <el-button
          v-if="canApplyRefund"
          size="large"
          round
          plain
          type="warning"
          :disabled="acting"
          @click="handleApplyRefund"
        >
          申请退款
        </el-button>

        <el-button
          v-if="canHandleRefund"
          size="large"
          round
          plain
          type="danger"
          :disabled="acting"
          @click="handleRejectRefund"
        >
          拒绝退款
        </el-button>

        <el-button
          v-if="canHandleRefund"
          size="large"
          round
          type="primary"
          :loading="acting"
          @click="handleAgreeRefund"
        >
          同意退款
        </el-button>

        <el-button
          v-if="canShip"
          size="large"
          round
          type="primary"
          :loading="acting"
          @click="handleShip"
        >
          发货
        </el-button>

        <el-button
          v-if="canReceive"
          size="large"
          round
          type="primary"
          :loading="acting"
          @click="handleReceive"
        >
          确认收货
        </el-button>

        <el-button
          v-if="canFinishFace"
          size="large"
          round
          type="primary"
          :loading="acting"
          @click="handleFinishFace"
        >
          确认已完成
        </el-button>

        <!-- 去支付：橙→金渐变，与其余页面保持同一视觉语言 -->
        <el-button
          v-if="canPay"
          class="order-detail__pay"
          size="large"
          :loading="acting"
          @click="handlePay"
        >
          去支付
        </el-button>
      </div>
    </template>

    <EmptyState
      v-else
      title="订单不存在或无权查看"
      description="可能是订单号有误，或者这笔订单不属于当前账号。"
      action-text="返回订单列表"
      @action="router.push({ name: 'order-list' })"
    />
  </main>
</template>

<style scoped lang="scss">
.order-detail {
  flex: 1;
  padding-top: 16px;
  padding-bottom: 40px;
  max-width: 860px;

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

  &__skeleton {
    @include cm-card;
    padding: 28px;
  }

  // ---------------- 状态头部 ----------------
  &__head {
    @include cm-card;
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 10px 16px;
    align-items: center;
    padding: 18px 22px;
    margin-bottom: 16px;
  }

  &__head-left {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__role-tag {
    padding: 2px 9px;
    border-radius: $cm-radius-pill;
    font-size: 11px;
    color: $cm-text-secondary;
    background: $cm-hover-bg;
  }

  &__head-right {
    text-align: right;
  }

  &__countdown {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: $cm-radius-pill;
    background: $cm-accent-50;
    color: $cm-accent-dark;
  }

  &__hint {
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__no {
    grid-column: 1 / -1;
    font-size: 12px;
    color: $cm-text-secondary;

    b {
      color: $cm-text;
      letter-spacing: 0.4px;
    }
  }

  &__alert {
    margin-bottom: 16px;
    border-radius: $cm-radius;
  }

  // ---------------- 通用区块 ----------------
  &__section-title {
    font-size: 15px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 16px;
    padding-left: 10px;
    border-left: 3px solid $cm-primary;
    line-height: 1.2;
  }

  &__product,
  &__info,
  &__seller {
    @include cm-card;
    padding: 20px 22px;
    margin-bottom: 16px;
  }

  // ---------------- 商品快照 ----------------
  &__product-row {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  &__cover {
    flex: none;
    width: 84px;
    border-radius: $cm-radius;
    overflow: hidden;
  }

  &__product-main {
    flex: 1;
    min-width: 0;
  }

  &__product-title {
    font-size: 15px;
    font-weight: 600;
    color: $cm-text;
    line-height: 1.45;
    margin-bottom: 8px;
    @include cm-ellipsis-lines(2);
  }

  &__product-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__deleted {
    padding: 1px 8px;
    border-radius: $cm-radius-pill;
    color: $cm-gray-tag;
    background: $cm-gray-tag-50;
  }

  &__product-amount {
    flex: none;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 2px;

    .cm-price {
      @include cm-price(22px);
    }
  }

  &__amount-label {
    font-size: 11px;
    color: $cm-text-placeholder;
  }

  // ---------------- 信息行 ----------------
  &__rows {
    display: flex;
    flex-direction: column;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 11px 0;
    border-bottom: 1px dashed $cm-border-light;

    &:last-child {
      border-bottom: none;
    }

    dt {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      flex: none;
      font-size: 13px;
      color: $cm-text-secondary;
      font-weight: 400;
    }

    dd {
      margin: 0;
      font-size: 13px;
      color: $cm-text;
      text-align: right;
      word-break: break-all;
    }
  }

  &__row-strong {
    font-weight: 600;
  }

  // ---------------- 卖家 ----------------
  &__seller-row {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  &__seller-avatar {
    @include cm-center;
    flex: none;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    font-size: 17px;
    font-weight: 700;
    color: #ffffff;
    background: $cm-gradient-brand;
    box-shadow: 0 4px 12px rgba(16, 185, 129, 0.26);
  }

  &__seller-main {
    min-width: 0;
  }

  &__seller-name {
    font-size: 15px;
    font-weight: 600;
    color: $cm-text;
  }

  &__seller-place {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-top: 3px;
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__seller-tip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-left: auto;
    flex: none;
    font-size: 12px;
    color: $cm-text-placeholder;
  }

  &__seller-fallback {
    font-size: 13px;
    color: $cm-text-secondary;
    line-height: 1.7;
  }

  // ---------------- 操作栏 ----------------
  &__bar {
    @include cm-card;
    position: sticky;
    bottom: 16px;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 12px;
    flex-wrap: wrap;
    padding: 14px 18px;
    margin-top: 16px;
    box-shadow: 0 6px 24px rgba(17, 24, 39, 0.12);
  }

  &__pay {
    height: 44px;
    min-width: 148px;
    padding: 0 26px;
    border: none;
    border-radius: $cm-radius;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: 2px;
    color: #ffffff;
    background: $cm-gradient-buy;
    box-shadow: $cm-shadow-buy;

    &:hover:not(.is-disabled) {
      transform: translateY(-2px);
      box-shadow: 0 6px 18px rgba(245, 158, 11, 0.42);
    }
  }

  @include cm-max($cm-bp-sm) {
    &__head {
      grid-template-columns: 1fr;
    }

    &__head-right {
      text-align: left;
    }

    &__product-row {
      flex-wrap: wrap;
    }

    &__product-amount {
      align-items: flex-start;
      width: 100%;
      margin-top: 8px;
    }

    &__seller-tip {
      display: none;
    }

    &__bar {
      justify-content: stretch;

      :deep(.el-button) {
        flex: 1;
      }
    }
  }
}
</style>
