<script setup>
/**
 * 订单状态标签（8 种状态全覆盖）
 *
 * 配色语义：
 *   待支付=橙  已支付=蓝  已发货=紫  已完成=绿
 *   已取消=灰  已冻结=灰(带锁)  退款申请中=黄  退款被拒=深橙
 */
import { computed } from 'vue'
import { Lock } from '@element-plus/icons-vue'
import { ORDER_STATUS_MAP } from '@/utils/constants'

const props = defineProps({
  /** 订单状态 0-7 */
  status: { type: [Number, String], default: null },
  /** 尺寸：sm 用于列表卡片，md 用于详情页头部 */
  size: { type: String, default: 'sm' }
})

const meta = computed(() => ORDER_STATUS_MAP[Number(props.status)] || { label: '状态未知', tone: 'gray' })
const isFrozen = computed(() => meta.value.tone === 'frozen')
</script>

<template>
  <span class="order-status" :class="[`is-${meta.tone}`, `is-${size}`]">
    <!-- 已冻结带锁图标：一眼看出是「异常终止」而不是普通完成 -->
    <el-icon v-if="isFrozen" class="order-status__icon" :size="size === 'md' ? 14 : 12">
      <Lock />
    </el-icon>
    {{ meta.label }}
  </span>
</template>

<style scoped lang="scss">
.order-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: $cm-radius-pill;
  font-weight: 600;
  white-space: nowrap;
  line-height: 1;

  &.is-sm {
    height: 24px;
    padding: 0 10px;
    font-size: 12px;
  }

  &.is-md {
    height: 30px;
    padding: 0 14px;
    font-size: 13px;
  }

  &__icon {
    flex: none;
  }

  // 待支付 = 橙（需要用户行动）
  &.is-orange {
    color: $cm-accent-dark;
    background: $cm-accent-50;
    border: 1px solid rgba($cm-accent, 0.35);
  }

  // 已支付待发货 = 蓝
  &.is-blue {
    color: #1d4ed8;
    background: $cm-blue-50;
    border: 1px solid rgba($cm-blue, 0.3);
  }

  // 已发货待收货 = 紫
  &.is-purple {
    color: #6d28d9;
    background: #f5f3ff;
    border: 1px solid rgba(139, 92, 246, 0.3);
  }

  // 已完成 = 绿（品牌色，正向终态）
  &.is-green {
    color: $cm-primary-700;
    background: $cm-primary-50;
    border: 1px solid rgba($cm-primary, 0.3);
  }

  // 已取消 = 灰
  &.is-gray {
    color: $cm-gray-tag;
    background: $cm-gray-tag-50;
    border: 1px solid rgba($cm-gray-tag, 0.22);
  }

  // 已冻结 = 灰底 + 锁，用更深一档的灰区分「异常」
  &.is-frozen {
    color: #475569;
    background: #f1f5f9;
    border: 1px solid rgba(71, 85, 105, 0.28);
  }

  // 退款申请中 = 黄（进行中，等待处理）
  &.is-yellow {
    color: #a16207;
    background: #fefce8;
    border: 1px solid rgba(234, 179, 8, 0.4);
  }

  // 退款被拒 = 深橙（有争议）
  &.is-darkorange {
    color: #c2410c;
    background: #fff7ed;
    border: 1px solid rgba(194, 65, 12, 0.32);
  }
}
</style>
