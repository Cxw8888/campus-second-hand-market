<script setup>
/**
 * 空状态占位（列表无数据时使用）
 */
defineProps({
  /** 标题 */
  title: { type: String, default: '暂无数据' },
  /** 补充说明 */
  description: { type: String, default: '' },
  /** 操作按钮文案，不传则不渲染按钮 */
  actionText: { type: String, default: '' }
})

const emit = defineEmits(['action'])
</script>

<template>
  <div class="cm-empty">
    <!-- 手绘的「空箱子」线条图：比 Element Plus 默认空状态更有校园手账感 -->
    <svg class="cm-empty__art" viewBox="0 0 160 120" width="160" height="120" aria-hidden="true">
      <ellipse cx="80" cy="104" rx="52" ry="7" fill="#E5E7EB" opacity="0.7" />
      <path
        d="M40 52h80v40a6 6 0 0 1-6 6H46a6 6 0 0 1-6-6z"
        fill="#ECFDF5"
        stroke="#10B981"
        stroke-width="2.4"
        stroke-linejoin="round"
      />
      <path d="M34 40h92v12H34z" fill="#D1FAE5" stroke="#10B981" stroke-width="2.4" stroke-linejoin="round" />
      <path d="M72 40v12M88 40v12" stroke="#10B981" stroke-width="2.4" stroke-linecap="round" />
      <path
        d="M62 72c6-8 16-10 24-6"
        fill="none"
        stroke="#A7F3D0"
        stroke-width="3"
        stroke-linecap="round"
      />
      <circle cx="126" cy="34" r="5" fill="#FCD34D" opacity="0.9" />
      <circle cx="34" cy="28" r="3.5" fill="#10B981" opacity="0.35" />
    </svg>

    <h3 class="cm-empty__title">{{ title }}</h3>
    <p v-if="description" class="cm-empty__desc">{{ description }}</p>

    <el-button v-if="actionText" type="primary" round class="cm-empty__action" @click="emit('action')">
      {{ actionText }}
    </el-button>
  </div>
</template>

<style scoped lang="scss">
.cm-empty {
  @include cm-center;
  flex-direction: column;
  padding: 56px 20px 64px;
  text-align: center;

  &__art {
    margin-bottom: 18px;
  }

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: $cm-text;
    margin-bottom: 6px;
  }

  &__desc {
    font-size: 13px;
    color: $cm-text-secondary;
    max-width: 420px;
    margin-bottom: 18px;
    line-height: 1.7;
  }

  &__action {
    padding: 0 22px;
  }
}
</style>
