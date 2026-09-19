<script setup>
/**
 * 下单成功页 /order/success/:orderId
 *
 * 三个要点：
 *   1. 大绿勾 + 订单号 + 金额 + 状态
 *   2. 倒计时「剩余 14:32 未支付将自动取消」；归零后自动重新拉一次状态
 *      —— 后端 ScheduledTasks 每分钟扫一次把超时订单置为 4-已取消，所以刷新后通常就能看到「已自动取消」
 *   3. 「立即支付」调 PUT /order/pay/{orderId}（模拟支付，真实资金线下流转）
 *
 * ⚠️ orderId 全程按字符串处理，绝不做 Number 转换（雪花 Long 会丢精度）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import PayCountdown from '@/components/PayCountdown.vue'
import { getOrderDetail, payOrder } from '@/api/order'
import { formatPrice } from '@/utils/format'
import { orderStatusHint, payTimeoutExpiredHint, payTimeoutHint, payTimeoutMinutes } from '@/utils/constants'

const route = useRoute()
const router = useRouter()

const orderId = computed(() => String(route.params.orderId || ''))

const loading = ref(true)
const paying = ref(false)
const order = ref(null)

const amount = computed(() => formatPrice(order.value?.amount))
const status = computed(() => Number(order.value?.status))
const isPending = computed(() => status.value === 0)
const isCancelled = computed(() => status.value === 4)
/**
 * 订单快照的 trade_type（OrderVO 已返回，随 getOrderDetail 一起来，不需要额外请求）。
 * 待支付窗口按它分档：面交 120 分钟 / 邮寄 15 分钟（批次 6.0.7）。
 */
const tradeType = computed(() => order.value?.tradeType)
const hint = computed(() => orderStatusHint(status.value, tradeType.value))

async function loadOrder() {
  try {
    order.value = await getOrderDetail(orderId.value, { silent: true })
  } catch (error) {
    console.warn('[order-success] 订单加载失败：', error?.message)
    ElMessage.error('订单信息加载失败')
  } finally {
    loading.value = false
  }
}

/** 倒计时归零：后端的超时取消任务可能已经把它改成 4-已取消，重新拉一次状态 */
async function handleExpire() {
  await loadOrder()
  if (Number(order.value?.status) === 4) {
    ElMessage.warning(`${payTimeoutExpiredHint(tradeType.value)}，订单已被系统自动取消`)
  }
}

async function handlePay() {
  try {
    await ElMessageBox.confirm(
      `即将支付 ¥${amount.value}。\n本系统为模拟支付，真实资金请在线下完成。`,
      '确认支付',
      { confirmButtonText: '确认支付', cancelButtonText: '取消', type: 'warning', customClass: 'cm-confirm-box' }
    )
  } catch {
    return
  }

  paying.value = true
  try {
    const data = await payOrder(orderId.value)
    order.value = data || order.value
    ElMessage.success('支付成功，等待卖家发货')
    // 支付成功直接进详情页，继续看时间线和后续操作
    router.replace({ name: 'order-detail', params: { orderId: orderId.value } })
  } catch (error) {
    console.warn('[order-success] 支付失败：', error?.message)
    // 209 状态不允许（例如已被超时取消）时刷新一下真实状态
    await loadOrder()
  } finally {
    paying.value = false
  }
}

function goDetail() {
  router.push({ name: 'order-detail', params: { orderId: orderId.value } })
}

function goHome() {
  router.push({ name: 'home' })
}

onMounted(loadOrder)
</script>

<template>
  <main class="order-success cm-container">
    <div v-if="loading" class="order-success__skeleton">
      <el-skeleton :rows="5" animated />
    </div>

    <section v-else-if="order" class="order-success__card">
      <!-- 大绿勾（已取消时换成灰圈叉，视觉上一眼区分） -->
      <div class="order-success__icon" :class="{ 'is-cancelled': isCancelled }">
        <svg v-if="!isCancelled" viewBox="0 0 96 96" width="96" height="96" aria-hidden="true">
          <circle cx="48" cy="48" r="42" fill="#ECFDF5" stroke="#10B981" stroke-width="3" />
          <path
            d="M30 49.5l12.5 12.5L67 36.5"
            fill="none"
            stroke="#10B981"
            stroke-width="6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <svg v-else viewBox="0 0 96 96" width="96" height="96" aria-hidden="true">
          <circle cx="48" cy="48" r="42" fill="#F3F4F6" stroke="#9CA3AF" stroke-width="3" />
          <path d="M34 34l28 28M62 34L34 62" stroke="#9CA3AF" stroke-width="6" stroke-linecap="round" />
        </svg>
      </div>

      <h1 class="order-success__heading">
        {{ isCancelled ? '订单已自动取消' : '下单成功' }}
      </h1>
      <p class="order-success__sub">
        {{
          isCancelled
            ? `${payTimeoutExpiredHint(tradeType)}，库存已回补，可以重新下单`
            : payTimeoutHint(tradeType)
        }}
      </p>

      <!-- 订单摘要 -->
      <dl class="order-success__info">
        <div class="order-success__row">
          <dt>订单号</dt>
          <dd class="cm-num order-success__no">{{ order.orderNo }}</dd>
        </div>
        <div class="order-success__row">
          <dt>商品</dt>
          <dd>{{ order.productTitle }}</dd>
        </div>
        <div class="order-success__row">
          <dt>订单金额</dt>
          <dd>
            <span class="cm-price order-success__amount">
              <span class="cm-price__symbol">¥</span>{{ amount }}
            </span>
          </dd>
        </div>
        <div class="order-success__row">
          <dt>订单状态</dt>
          <dd><OrderStatusTag :status="order.status" size="md" /></dd>
        </div>
      </dl>

      <!-- 倒计时：只在待支付时出现（窗口按订单 trade_type 分档：面交 120 分钟 / 邮寄 15 分钟） -->
      <div v-if="isPending" class="order-success__countdown">
        <PayCountdown :create-time="order.createTime" :trade-type="tradeType" @expire="handleExpire" />
      </div>
      <p v-else-if="hint" class="order-success__hint">{{ hint }}</p>

      <!-- 操作 -->
      <div class="order-success__actions">
        <el-button
          v-if="isPending"
          class="order-success__pay"
          size="large"
          :loading="paying"
          @click="handlePay"
        >
          立即支付
        </el-button>

        <el-button size="large" round class="order-success__secondary" @click="goDetail">
          查看订单
        </el-button>

        <el-button size="large" link class="order-success__link" @click="goHome">返回首页</el-button>
      </div>
    </section>

    <EmptyState
      v-else
      title="订单不存在或无权查看"
      description="请确认订单号是否正确，或者回首页看看其它商品。"
      action-text="返回首页"
      @action="goHome"
    />
  </main>
</template>

<style scoped lang="scss">
.order-success {
  flex: 1;
  padding-top: 40px;
  padding-bottom: 56px;
  max-width: 620px;

  &__skeleton {
    @include cm-card;
    padding: 28px;
  }

  &__card {
    @include cm-card;
    padding: 40px 36px 34px;
    text-align: center;
  }

  &__icon {
    display: flex;
    justify-content: center;
    margin-bottom: 18px;
    animation: cm-pop 0.42s cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  &__heading {
    font-size: 24px;
    font-weight: 800;
    color: $cm-text;
    margin-bottom: 8px;
  }

  &__sub {
    font-size: 13px;
    color: $cm-text-secondary;
    line-height: 1.7;
    margin-bottom: 26px;
  }

  // ---------------- 订单摘要 ----------------
  &__info {
    text-align: left;
    border-top: 1px dashed $cm-border;
    padding-top: 8px;
    margin-bottom: 20px;
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
      flex: none;
      font-size: 13px;
      color: $cm-text-secondary;
    }

    dd {
      margin: 0;
      font-size: 13px;
      font-weight: 600;
      color: $cm-text;
      text-align: right;
      min-width: 0;
      word-break: break-all;
    }
  }

  &__no {
    font-family: 'SFMono-Regular', Consolas, monospace;
    letter-spacing: 0.4px;
    font-weight: 600;
  }

  &__amount {
    @include cm-price(22px);
  }

  &__countdown {
    margin-bottom: 22px;
    padding: 12px 16px;
    border-radius: $cm-radius;
    background: $cm-accent-50;
    border: 1px dashed rgba($cm-accent, 0.45);
  }

  &__hint {
    font-size: 13px;
    color: $cm-text-secondary;
    margin-bottom: 22px;
  }

  // ---------------- 操作区 ----------------
  &__actions {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
  }

  // 立即支付：与「立即购买/提交订单」同一套橙→金渐变
  &__pay {
    width: 100%;
    height: 48px;
    border: none;
    border-radius: $cm-radius;
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 3px;
    color: #ffffff;
    background: $cm-gradient-buy;
    box-shadow: $cm-shadow-buy;

    &:hover:not(.is-disabled) {
      transform: translateY(-2px);
      box-shadow: 0 6px 18px rgba(245, 158, 11, 0.42);
    }
  }

  &__secondary {
    width: 100%;
    height: 44px;
  }

  &__link {
    color: $cm-text-secondary;

    &:hover {
      color: $cm-primary;
    }
  }
}

@keyframes cm-pop {
  0% {
    transform: scale(0.6);
    opacity: 0;
  }

  100% {
    transform: scale(1);
    opacity: 1;
  }
}
</style>
