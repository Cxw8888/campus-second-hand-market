<script setup>
/**
 * 品牌标识（Logo 图形 + 平台名称）
 *
 * 图形是内联 SVG：一枚白色叶子 + 循环箭头，呼应「绿色循环 / 让闲置流动」的主题。
 * 内联而不是外部图片，是为了零资源依赖（不会 404，也不用打包处理图片）。
 */
defineProps({
  /** 图标尺寸（px） */
  size: { type: Number, default: 32 },
  /** 是否显示文字 */
  showText: { type: Boolean, default: true },
  /** 配色：dark 用于白底导航栏，light 用于绿色渐变底（左侧品牌区） */
  variant: { type: String, default: 'dark' }
})
</script>

<template>
  <div class="cm-logo" :class="`is-${variant}`">
    <span class="cm-logo__mark" :style="{ width: `${size}px`, height: `${size}px` }">
      <svg viewBox="0 0 32 32" width="100%" height="100%" aria-hidden="true">
        <!-- 外圈：不闭合的圆弧，暗示「循环」 -->
        <path
          d="M16 3.5a12.5 12.5 0 1 0 12.5 12.5"
          fill="none"
          stroke="currentColor"
          stroke-width="2.6"
          stroke-linecap="round"
        />
        <!-- 圆弧末端箭头 -->
        <path d="M28.5 16l-3.4-2.6v5.2z" fill="currentColor" />
        <!-- 叶子：中线 + 两瓣 -->
        <path
          d="M16 9.5c4.6 1.9 6.6 5.4 6.6 9.1 0 3.7-2.9 6.6-6.6 6.6s-6.6-2.9-6.6-6.6c0-3.7 2-7.2 6.6-9.1z"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linejoin="round"
        />
        <path d="M16 11.5v13.4" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      </svg>
    </span>

    <span v-if="showText" class="cm-logo__text">
      <span class="cm-logo__name">校园二手交易</span>
      <span class="cm-logo__slogan">让闲置流动起来</span>
    </span>
  </div>
</template>

<style scoped lang="scss">
.cm-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;

  &__mark {
    display: inline-flex;
    flex: none;
  }

  &__text {
    display: flex;
    flex-direction: column;
    line-height: 1.15;
  }

  &__name {
    font-size: 17px;
    font-weight: 700;
    letter-spacing: 0.5px;
  }

  &__slogan {
    font-size: 11px;
    letter-spacing: 1.5px;
    opacity: 0.72;
  }

  // 白底导航栏：记号是品牌绿，文字深色
  &.is-dark {
    .cm-logo__mark {
      color: $cm-primary;
    }

    .cm-logo__name {
      color: $cm-text;
    }

    .cm-logo__slogan {
      color: $cm-text-secondary;
    }
  }

  // 绿色渐变底：整体反白
  &.is-light {
    .cm-logo__mark,
    .cm-logo__name,
    .cm-logo__slogan {
      color: #ffffff;
    }

    .cm-logo__slogan {
      opacity: 0.8;
    }
  }
}
</style>
