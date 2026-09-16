<script setup>
/**
 * 交易方式标签（校园二手特色：面交 / 邮寄 / 皆可）
 *
 * 放在这里而不是每个页面各写一遍的原因，和 ConditionTag / OrderStatusTag 一样：
 * 配色语义必须全站唯一。之前列表页、订单卡片、下单页各写了一个 pill，
 * 颜色各不相同（有的是深色 chip、有的是统一绿色），既不像同一套设计，也看不出「面交 vs 邮寄」的区别。
 *
 * 配色语义（取自 constants.js 的 TRADE_TYPE_MAP.tone，不在这里硬编码）：
 *   仅面交(1) → 清新绿（success）：校园主流交易方式，正向、轻快
 *   仅邮寄(2) → 蓝（primary）：需要物流，偏"事务性"
 *   两者皆可(3) → 暖橙（warning）：给买家留了选择空间，需要留意
 */
import { computed } from 'vue'
import { TRADE_TYPE_MAP } from '@/utils/constants'

const props = defineProps({
  /** 交易方式：1-仅面交, 2-仅邮寄, 3-两者皆可 */
  type: { type: [Number, String], default: null },
  /** 尺寸：sm 用于卡片/列表，md 用于详情页信息区 */
  size: { type: String, default: 'sm' }
})

const meta = computed(() => TRADE_TYPE_MAP[Number(props.type)] || { label: '方式待定', tone: 'gray' })
</script>

<template>
  <span class="trade-tag" :class="[`is-${meta.tone}`, `is-${size}`]">{{ meta.label }}</span>
</template>

<style scoped lang="scss">
.trade-tag {
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

  // 仅面交 → 清新绿（品牌色，表达「校园内、最省事」）
  &.is-green {
    color: $cm-primary-700;
    background: $cm-primary-50;
    border-color: rgba($cm-primary, 0.28);
  }

  // 仅邮寄 → 蓝
  &.is-blue {
    color: #1d4ed8;
    background: $cm-blue-50;
    border-color: rgba($cm-blue, 0.28);
  }

  // 两者皆可 → 暖橙
  &.is-orange {
    color: $cm-accent-dark;
    background: $cm-accent-50;
    border-color: rgba($cm-accent, 0.32);
  }

  // 兜底（数据异常 / 未填）
  &.is-gray {
    color: $cm-gray-tag;
    background: $cm-gray-tag-50;
    border-color: rgba($cm-gray-tag, 0.22);
  }
}
</style>
