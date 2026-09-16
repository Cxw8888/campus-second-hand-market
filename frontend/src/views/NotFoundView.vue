<script setup>
/**
 * 404 页面
 * 居中排版 + 返回首页按钮；插画同样是手写 SVG（找不到的「空货架」）。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="not-found">
    <span class="not-found__topline" aria-hidden="true" />

    <div class="not-found__inner">
      <svg class="not-found__art" viewBox="0 0 220 160" width="220" height="160" aria-hidden="true">
        <ellipse cx="110" cy="140" rx="66" ry="9" fill="#E5E7EB" opacity="0.65" />
        <!-- 空货架 -->
        <rect x="42" y="30" width="136" height="104" rx="10" fill="#FFFFFF" stroke="#10B981" stroke-width="2.6" />
        <path d="M42 68h136M42 104h136" stroke="#A7F3D0" stroke-width="2.6" />
        <path d="M52 24v116M168 24v116" stroke="#10B981" stroke-width="2.6" stroke-linecap="round" />
        <!-- 货架上的「问号」 -->
        <path
          d="M110 48c-8 0-13 4-14 11"
          fill="none"
          stroke="#F59E0B"
          stroke-width="3"
          stroke-linecap="round"
        />
        <circle cx="110" cy="118" r="3.5" fill="#F59E0B" />
        <!-- 装饰 -->
        <circle cx="28" cy="52" r="4.5" fill="#10B981" opacity="0.35" />
        <circle cx="196" cy="86" r="4" fill="#FCD34D" opacity="0.85" />
      </svg>

      <h1 class="not-found__code">404</h1>
      <h2 class="not-found__title">这个摊位不存在</h2>
      <p class="not-found__desc">你访问的页面可能已被下架、删除，或者链接拼错了。</p>

      <div class="not-found__actions">
        <el-button type="primary" round size="large" @click="router.push({ name: 'home' })">
          返回首页
        </el-button>
        <el-button round size="large" plain @click="router.back()">返回上一页</el-button>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
.not-found {
  position: relative;
  min-height: 100vh;
  @include cm-center;
  background:
    radial-gradient(900px 420px at 50% -10%, rgba(16, 185, 129, 0.1), transparent 70%),
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
    font-size: 56px;
    font-weight: 800;
    line-height: 1;
    letter-spacing: 4px;
    background: $cm-gradient-brand;
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
    margin-bottom: 10px;
  }

  &__title {
    font-size: 20px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 8px;
  }

  &__desc {
    font-size: 14px;
    color: $cm-text-secondary;
    margin-bottom: 28px;
  }

  &__actions {
    display: flex;
    justify-content: center;
    gap: 12px;
  }
}
</style>
