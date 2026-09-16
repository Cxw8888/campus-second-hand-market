<script setup>
/**
 * 商品状态标签（4 种状态）
 *
 * 配色语义取自 PRODUCT_STATUS_MAP.tone（不在这里硬编码），与全站一致：
 *   在售(1)   = 绿 —— 正向、可交易
 *   待审核(3) = 蓝 —— 进行中，等着被人处理
 *   已售罄(2) = 橙 —— 需要注意，校园二手库存常为 1，这是高频状态
 *   已下架(0) = 灰 —— 终止（含审核不通过）
 *
 * 说明：MyProductCard 里目前还留着自己的一小段状态样式（批次 4 的做法），
 * 本批**没有**顺手改它 —— 那是已经验收过的页面，无理由重构只会引入风险。
 * 后续若要统一，把它替换成本组件即可。
 */
import { computed } from 'vue'
import { PRODUCT_STATUS_MAP } from '@/utils/constants'

const props = defineProps({
  /** 商品状态：0-下架/审核不通过, 1-上架中, 2-售罄, 3-待审核 */
  status: { type: [Number, String], default: null },
  /** 尺寸：sm 用于表格/卡片，md 用于详情页信息区 */
  size: { type: String, default: 'sm' }
})

const meta = computed(
  () => PRODUCT_STATUS_MAP[Number(props.status)] || { label: '状态未知', tone: 'gray' }
)
</script>

<template>
  <span class="product-status" :class="[`is-${meta.tone}`, `is-${size}`]">{{ meta.label }}</span>
</template>

<style scoped lang="scss">
.product-status {
  display: inline-flex;
  align-items: center;
  border-radius: $cm-radius-pill;
  font-weight: 600;
  white-space: nowrap;
  line-height: 1;
  border: 1px solid transparent;

  &.is-sm {
    height: 22px;
    padding: 0 9px;
    font-size: 11px;
  }

  &.is-md {
    height: 26px;
    padding: 0 12px;
    font-size: 12px;
  }

  // 在售 → 品牌绿
  &.is-green {
    color: $cm-primary-700;
    background: $cm-primary-50;
    border-color: rgba($cm-primary, 0.28);
  }

  // 待审核 → 蓝
  &.is-blue {
    color: #1d4ed8;
    background: $cm-blue-50;
    border-color: rgba($cm-blue, 0.28);
  }

  // 已售罄 → 暖橙
  &.is-orange {
    color: $cm-accent-dark;
    background: $cm-accent-50;
    border-color: rgba($cm-accent, 0.32);
  }

  // 已下架 → 灰
  &.is-gray {
    color: $cm-gray-tag;
    background: $cm-gray-tag-50;
    border-color: rgba($cm-gray-tag, 0.22);
  }
}
</style>
