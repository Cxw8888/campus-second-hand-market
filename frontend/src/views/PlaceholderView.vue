<script setup>
/**
 * 占位页（给尚未实现的批次兜底）
 *
 * 用同一个组件 + 路由 meta.placeholder 配置，承载「我的收藏 / 消息中心」等后续批次页面。
 * 好处是导航、按钮、登录拦截这些链路现在就能完整演示，而且不会出现 404，
 * 答辩时可以说清「哪些已完成、哪些是下一阶段」。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, ShoppingCart, User, List, Star, Bell } from '@element-plus/icons-vue'

const route = useRoute()

/** 路由 meta 里声明的图标名 → 组件对象（只映射用到的几个，便于 tree-shaking） */
const ICON_MAP = { Plus, ShoppingCart, User, List, Star, Bell }

const config = computed(() => route.meta?.placeholder || {})
const iconComponent = computed(() => ICON_MAP[config.value.icon] || Plus)

/** 下单占位页要把 productId 显示出来，证明参数确实透传到了这一层 */
const showProductId = computed(() => Boolean(config.value.showProductId))
const productId = computed(() => route.query.productId || '')
</script>

<template>
  <main class="placeholder cm-container">
    <section class="placeholder__card">
      <span class="placeholder__badge">第二批开放</span>

      <el-icon class="placeholder__icon" :size="42">
        <component :is="iconComponent" />
      </el-icon>

      <h1 class="placeholder__title">{{ config.heading || '页面建设中' }}</h1>
      <p class="placeholder__desc">{{ config.description }}</p>

      <div v-if="showProductId" class="placeholder__param">
        <span class="placeholder__param-label">已接收到的商品 ID</span>
        <code class="placeholder__param-value">{{ productId || '（未传 productId）' }}</code>
      </div>

      <div class="placeholder__actions">
        <el-button type="primary" round @click="$router.push({ name: 'home' })">返回首页</el-button>
        <el-button round plain @click="$router.back()">返回上一页</el-button>
      </div>
    </section>
  </main>
</template>

<style scoped lang="scss">
.placeholder {
  padding: 56px 20px 72px;

  &__card {
    @include cm-card;
    max-width: 560px;
    margin: 0 auto;
    padding: 46px 36px 40px;
    text-align: center;
    position: relative;
  }

  &__badge {
    position: absolute;
    top: 18px;
    right: 18px;
    padding: 3px 12px;
    border-radius: $cm-radius-pill;
    font-size: 12px;
    color: $cm-primary-700;
    background: $cm-primary-50;
    border: 1px solid rgba($cm-primary, 0.25);
  }

  &__icon {
    color: $cm-primary;
    background: $cm-primary-50;
    width: 78px;
    height: 78px;
    border-radius: 22px;
    margin-bottom: 20px;
  }

  &__title {
    font-size: 22px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 12px;
  }

  &__desc {
    font-size: 14px;
    color: $cm-text-secondary;
    line-height: 1.8;
    margin-bottom: 22px;
  }

  &__param {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    padding: 10px 16px;
    border-radius: $cm-radius;
    background: $cm-accent-50;
    border: 1px dashed rgba($cm-accent, 0.45);
    margin-bottom: 26px;
  }

  &__param-label {
    font-size: 12px;
    color: $cm-accent-dark;
  }

  &__param-value {
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 14px;
    font-weight: 700;
    color: $cm-accent-dark;
  }

  &__actions {
    display: flex;
    justify-content: center;
    gap: 12px;
  }
}
</style>
