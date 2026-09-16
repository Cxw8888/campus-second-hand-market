<script setup>
/**
 * 订单列表卡片
 *
 * 展示：商品标题（订单快照，不是实时商品名）、订单号、金额、交易方式、状态标签、下单相对时间。
 *
 * ⚠️ 订单号直接渲染字符串，**绝不做 Number/parseInt 转换** ——
 * 雪花算法生成的 Long 超出 JS 安全整数范围（2^53-1），转数字会静默丢精度，
 * 导致显示的订单号和后端对不上。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import ProductImage from '@/components/ProductImage.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { formatPrice, formatRelativeTime, formatDate } from '@/utils/format'
import { orderStatusHint } from '@/utils/constants'

const props = defineProps({
  /** 后端 OrderVO */
  order: { type: Object, required: true },
  /** 当前视角：buyer=我买到的，seller=我卖出的（只影响文案） */
  role: { type: String, default: 'buyer' }
})

const router = useRouter()

const amount = computed(() => formatPrice(props.order.amount))
const unitPrice = computed(() => formatPrice(props.order.productPrice))
const relativeTime = computed(() => formatRelativeTime(props.order.createTime))
const absoluteTime = computed(() => formatDate(props.order.createTime))
const hint = computed(() => orderStatusHint(props.order.status))
/** 商品被逻辑删除时后端会给 productDeleted=true，此时只展示快照、不给跳商品详情 */
const productDeleted = computed(() => Boolean(props.order.productDeleted))

function openDetail() {
  router.push({ name: 'order-detail', params: { orderId: String(props.order.id) } })
}
</script>

<template>
  <article class="order-card" @click="openDetail">
    <!-- 左：商品封面（商品已删除时后端返回空，走 ProductImage 的占位兜底） -->
    <div class="order-card__cover">
      <ProductImage :src="order.productCover" :alt="order.productTitle" ratio="1 / 1" :icon-size="22" />
    </div>

    <!-- 中：主信息 -->
    <div class="order-card__main">
      <h3 class="order-card__title">{{ order.productTitle || '商品信息已不可见' }}</h3>

      <div class="order-card__meta">
        <span class="order-card__no">
          订单号 <b class="cm-num">{{ order.orderNo }}</b>
        </span>
        <span v-if="productDeleted" class="order-card__deleted">商品已删除</span>
      </div>

      <div class="order-card__tags">
        <!-- 交易方式三色标签：面交绿 / 邮寄蓝 / 皆可橙 -->
        <TradeTypeTag :type="order.tradeType" size="sm" />
        <span class="order-card__time">
          {{ relativeTime }}
          <span class="order-card__time-abs">({{ absoluteTime }})</span>
        </span>
      </div>
    </div>

    <!-- 右：金额 + 状态 -->
    <div class="order-card__side">
      <OrderStatusTag :status="order.status" size="sm" />

      <div class="order-card__amount">
        <span class="cm-price">
          <span class="cm-price__symbol">¥</span>{{ amount }}
        </span>
        <span class="order-card__qty">×{{ order.quantity }}</span>
      </div>

      <div v-if="role === 'seller' && !productDeleted" class="order-card__unit">单价 ¥{{ unitPrice }}</div>
    </div>

    <!-- hover 时才出现的一行提示：告诉用户这单下一步该干嘛 -->
    <div v-if="hint" class="order-card__hint">{{ hint }}</div>
  </article>
</template>

<style scoped lang="scss">
.order-card {
  @include cm-card;
  @include cm-hover-lift(-2px);
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 16px;
  padding: 16px;
  cursor: pointer;

  &__cover {
    flex: none;
    width: 92px;
    border-radius: $cm-radius;
    overflow: hidden;
  }

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: $cm-text;
    line-height: 1.45;
    @include cm-ellipsis-lines(2);
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__no b {
    color: $cm-text;
    font-weight: 600;
    letter-spacing: 0.3px;
  }

  &__deleted {
    padding: 1px 7px;
    border-radius: $cm-radius-pill;
    font-size: 11px;
    color: $cm-gray-tag;
    background: $cm-gray-tag-50;
  }

  &__tags {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: auto;
  }

  // 交易方式标签的样式已由 TradeTypeTag 统一提供（原来是所有方式都用绿色，区分不出面交/邮寄）
  &__time {
    font-size: 12px;
    color: $cm-text-placeholder;
  }

  &__time-abs {
    font-size: 11px;
  }

  &__side {
    flex: none;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 10px;
    min-width: 130px;
  }

  &__amount {
    display: flex;
    align-items: baseline;
    gap: 6px;
    margin-top: auto;
  }

  &__qty {
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__unit {
    font-size: 11px;
    color: $cm-text-placeholder;
  }

  // 底部提示条：默认隐藏，hover 时展开（不占常态空间，列表更紧凑）
  &__hint {
    position: absolute;
    left: 16px;
    right: 16px;
    bottom: 8px;
    font-size: 11px;
    color: $cm-primary-700;
    opacity: 0;
    transform: translateY(4px);
    transition:
      opacity 0.2s ease,
      transform 0.2s ease;
    pointer-events: none;
  }

  &:hover &__hint {
    opacity: 1;
    transform: translateY(0);
  }

  @include cm-max($cm-bp-sm) {
    &__side {
      min-width: 96px;
    }

    &__time-abs {
      display: none;
    }
  }
}
</style>
