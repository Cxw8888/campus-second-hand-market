<script setup>
/**
 * 成色标签
 *
 * 色彩语义（与设计规范一致）：
 *   全新 = 绿  几乎全新 = 蓝  轻微使用 = 橙  明显使用 = 灰
 * 取值到文案/色调的映射统一放在 utils/constants.js，避免各处硬编码。
 */
import { computed } from 'vue'
import { conditionLabel, conditionTone } from '@/utils/constants'

const props = defineProps({
  /** 成色：1-4 */
  level: { type: [Number, String], default: null },
  /** 尺寸：sm 用于卡片，md 用于详情页 */
  size: { type: String, default: 'sm' }
})

const label = computed(() => conditionLabel(props.level))
const tone = computed(() => conditionTone(props.level))
</script>

<template>
  <span class="cm-condition" :class="[`is-${tone}`, `is-${size}`]">{{ label }}</span>
</template>

<style scoped lang="scss">
.cm-condition {
  display: inline-flex;
  align-items: center;
  border-radius: $cm-radius-pill;
  font-weight: 500;
  white-space: nowrap;
  line-height: 1;
  border: 1px solid transparent;

  &.is-sm {
    height: 22px;
    padding: 0 9px;
    font-size: 12px;
  }

  &.is-md {
    height: 28px;
    padding: 0 12px;
    font-size: 13px;
  }

  // 全新：绿（复用品牌绿，表达「品相最好」）
  &.is-green {
    color: $cm-primary-700;
    background: $cm-primary-50;
    border-color: rgba($cm-primary, 0.28);
  }

  // 几乎全新：蓝
  &.is-blue {
    color: #1d4ed8;
    background: $cm-blue-50;
    border-color: rgba($cm-blue, 0.28);
  }

  // 轻微使用：橙（交易强调色的浅色版）
  &.is-orange {
    color: $cm-accent-dark;
    background: $cm-accent-50;
    border-color: rgba($cm-accent, 0.32);
  }

  // 明显使用 / 未知：灰
  &.is-gray {
    color: $cm-gray-tag;
    background: $cm-gray-tag-50;
    border-color: rgba($cm-gray-tag, 0.22);
  }
}
</style>
