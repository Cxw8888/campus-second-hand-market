<script setup>
/**
 * 商品卡片
 *
 * 视觉规范落地：
 *   · 白底 + 12px 圆角 + 柔和阴影，hover 上浮 4px（cm-card / cm-hover-lift 混入）
 *   · 价格：¥ 小一号 + 数字大号加粗 + 橙色
 *   · 成色标签走 ConditionTag（绿/蓝/橙/灰四色语义）
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import ProductImage from '@/components/ProductImage.vue'
import ConditionTag from '@/components/ConditionTag.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { formatPrice, formatDate } from '@/utils/format'

const props = defineProps({
  /** 后端 ProductListVO（也兼容本地 mock 的同形状对象） */
  product: { type: Object, required: true }
})

const router = useRouter()

const price = computed(() => formatPrice(props.product.price))
const sellerName = computed(() => props.product.sellerNickname || '匿名同学')
const sellerInitial = computed(() => sellerName.value.trim().charAt(0) || '同')
const createdDate = computed(() => formatDate(props.product.createTime))

/** 售罄：status=2 或库存为 0，卡片上盖一层蒙版 */
const soldOut = computed(() => Number(props.product.status) === 2 || Number(props.product.stock) === 0)

function openDetail() {
  router.push({ name: 'product-detail', params: { id: props.product.id } })
}
</script>

<template>
  <article class="product-card" :class="{ 'is-sold-out': soldOut }" @click="openDetail">
    <div class="product-card__cover">
      <!--
        列表页优先用 400px 缩略图（6.0.5.2 · M6-A4）：原图最大可达 8192px，列表页白耗流量。
        thumbUrl 为 null（演示数据 / 非本地上传图）或缩略图 404 时，
        ProductImage 会自动降级到 coverImage，不会退化成「暂无图片」。
      -->
      <ProductImage
        :src="product.thumbUrl || product.coverImage"
        :fallback-src="product.coverImage"
        :alt="product.title"
        ratio="4 / 3"
      />
      <span v-if="soldOut" class="product-card__sold-out">已售罄</span>
      <!-- 交易方式三色标签：面交绿 / 邮寄蓝 / 皆可橙 -->
      <TradeTypeTag class="product-card__trade" :type="product.tradeType" size="sm" />
    </div>

    <div class="product-card__body">
      <h3 class="product-card__title">{{ product.title }}</h3>

      <div class="product-card__price-row">
        <span class="cm-price">
          <span class="cm-price__symbol">¥</span>{{ price }}
        </span>
        <ConditionTag :level="product.conditionLevel" size="sm" />
      </div>

      <div class="product-card__footer">
        <span class="product-card__seller">
          <span class="product-card__avatar">{{ sellerInitial }}</span>
          <span class="product-card__seller-name">{{ sellerName }}</span>
        </span>
        <span v-if="createdDate" class="product-card__date">{{ createdDate }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
.product-card {
  @include cm-card;
  @include cm-hover-lift(-4px);
  overflow: hidden;
  cursor: pointer;
  display: flex;
  flex-direction: column;

  &__cover {
    position: relative;
    overflow: hidden;

    // hover 时封面轻微放大，卡片「活」起来
    :deep(.cm-image__img) {
      transition: transform 0.35s ease;
    }
  }

  &:hover &__cover :deep(.cm-image__img) {
    transform: scale(1.06);
  }

  // 交易方式标签：只负责把它定位到封面左上角，
  // 具体配色由 TradeTypeTag 统一决定（原来是深色半透明 chip，看不出面交/邮寄的区别）
  &__trade {
    position: absolute;
    left: 10px;
    top: 10px;
  }

  &__sold-out {
    position: absolute;
    right: 10px;
    top: 10px;
    padding: 2px 10px;
    border-radius: $cm-radius-pill;
    font-size: 11px;
    font-weight: 600;
    color: #ffffff;
    background: $cm-accent;
  }

  &__body {
    padding: 12px 14px 14px;
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
  }

  &__title {
    font-size: 14px;
    font-weight: 600;
    line-height: 1.45;
    color: $cm-text;
    min-height: 40px; // 固定两行高度，网格才不会参差不齐
    @include cm-ellipsis-lines(2);
  }

  &__price-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  &__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding-top: 10px;
    border-top: 1px solid $cm-border-light;
    margin-top: auto;
  }

  &__seller {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  }

  // 文字头像：不依赖任何图片资源
  &__avatar {
    @include cm-center;
    flex: none;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    font-size: 11px;
    font-weight: 600;
    color: $cm-primary-700;
    background: $cm-primary-100;
  }

  &__seller-name {
    font-size: 12px;
    color: $cm-text-secondary;
    @include cm-ellipsis;
  }

  &__date {
    flex: none;
    font-size: 11px;
    color: $cm-text-placeholder;
  }

  &.is-sold-out {
    .product-card__cover {
      filter: grayscale(0.35);
    }

    .cm-price {
      color: $cm-text-secondary;
    }
  }
}
</style>
