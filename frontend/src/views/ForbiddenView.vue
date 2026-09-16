<script setup>
/**
 * 403 无权限页
 *
 * 使用场景：编辑别人的商品被拦下（前端先查 sellerId，后端 PUT 也会用 203 兜底）。
 * 与 404 页区分开，是为了让「不是找不到，而是没权限」这件事对用户可见。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="forbidden">
    <span class="forbidden__topline" aria-hidden="true" />

    <div class="forbidden__inner">
      <svg class="forbidden__art" viewBox="0 0 200 150" width="200" height="150" aria-hidden="true">
        <rect x="52" y="34" width="96" height="80" rx="12" fill="#FFFFFF" stroke="#F59E0B" stroke-width="2.6" />
        <path d="M52 62h96" stroke="#FDE68A" stroke-width="2.6" />
        <circle cx="100" cy="88" r="14" fill="none" stroke="#10B981" stroke-width="3" />
        <path d="M100 96v-8" stroke="#10B981" stroke-width="3" stroke-linecap="round" />
        <circle cx="100" cy="80" r="2.4" fill="#10B981" />
        <circle cx="34" cy="52" r="5" fill="#F59E0B" opacity="0.35" />
        <circle cx="168" cy="96" r="4" fill="#10B981" opacity="0.35" />
      </svg>

      <h1 class="forbidden__code">403</h1>
      <h2 class="forbidden__title">没有权限访问</h2>
      <p class="forbidden__desc">
        这个页面只能由商品发布者本人操作。<br />
        如果你确实需要修改，请使用发布该商品时所用的账号登录。
      </p>

      <div class="forbidden__actions">
        <el-button type="primary" round size="large" @click="router.push({ name: 'product-my' })">
          去我的商品
        </el-button>
        <el-button round size="large" plain @click="router.push({ name: 'home' })">返回首页</el-button>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
.forbidden {
  position: relative;
  min-height: 100vh;
  @include cm-center;
  background:
    radial-gradient(900px 420px at 50% -10%, rgba(245, 158, 11, 0.1), transparent 70%),
    $cm-bg;

  &__topline {
    position: absolute;
    inset: 0 0 auto 0;
    height: 4px;
    background: $cm-gradient-topbar;
  }

  &__inner {
    text-align: center;
    padding: 40px 20px;
  }

  &__art {
    margin: 0 auto 8px;
  }

  &__code {
    font-size: 52px;
    font-weight: 800;
    line-height: 1;
    letter-spacing: 4px;
    color: $cm-accent;
    margin-bottom: 10px;
  }

  &__title {
    font-size: 20px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 10px;
  }

  &__desc {
    font-size: 13px;
    color: $cm-text-secondary;
    line-height: 1.9;
    margin-bottom: 26px;
  }

  &__actions {
    display: flex;
    justify-content: center;
    gap: 12px;
  }
}
</style>
