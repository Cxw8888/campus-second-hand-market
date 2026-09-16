<script setup>
/**
 * 登录/注册页左侧品牌插画区
 *
 * 设计说明：
 *   · 背景：品牌绿三段渐变（深 → 主 → 浅），是全局唯一大面积使用渐变的地方之一
 *   · 插画：纯手写内联 SVG —— 循环箭头环 + 三张「闲置好物」卡片（书 / 耳机 / 绿植），
 *           用线条几何而不是写实插画，缩放到任何尺寸都不会糊，也不依赖任何图片资源
 *   · 文案：平台名称 + slogan + 三条卖点
 */
import BrandLogo from '@/components/BrandLogo.vue'

/** 三条卖点，只在此处维护 */
const highlights = [
  { title: '校园实地面交', desc: '同校同学，约个地点就能交易' },
  { title: '邮箱实名认证', desc: '校园邮箱注册，身份更可信' },
  { title: '闲置循环利用', desc: '把吃灰的东西换成生活费' }
]
</script>

<template>
  <aside class="brand-panel">
    <!-- 背景柔光斑：两个大模糊圆，让纯色渐变不那么平 -->
    <span class="brand-panel__blob brand-panel__blob--a" aria-hidden="true" />
    <span class="brand-panel__blob brand-panel__blob--b" aria-hidden="true" />

    <div class="brand-panel__inner">
      <BrandLogo :size="38" variant="light" />

      <div class="brand-panel__art">
        <svg viewBox="0 0 320 300" width="100%" height="100%" role="img" aria-label="闲置好物循环流动插画">
          <!-- 循环环：不闭合的圆弧 + 两个箭头，表达「流动 / 循环」 -->
          <circle
            cx="160"
            cy="150"
            r="104"
            fill="none"
            stroke="#ffffff"
            stroke-width="2"
            stroke-opacity="0.35"
            stroke-dasharray="10 12"
          />
          <path
            d="M56 150a104 104 0 0 1 104-104"
            fill="none"
            stroke="#ffffff"
            stroke-width="3"
            stroke-opacity="0.85"
            stroke-linecap="round"
          />
          <path d="M160 34l-11 7 11 7z" fill="#ffffff" fill-opacity="0.85" />
          <path
            d="M264 150a104 104 0 0 1-104 104"
            fill="none"
            stroke="#ffffff"
            stroke-width="3"
            stroke-opacity="0.85"
            stroke-linecap="round"
          />
          <path d="M160 266l11-7-11-7z" fill="#ffffff" fill-opacity="0.85" />

          <!-- 卡片 1：书本（教材书籍） -->
          <g transform="translate(122 22)">
            <rect width="76" height="76" rx="18" fill="#ffffff" fill-opacity="0.16" />
            <rect
              x="0.75"
              y="0.75"
              width="74.5"
              height="74.5"
              rx="17.25"
              fill="none"
              stroke="#ffffff"
              stroke-opacity="0.45"
              stroke-width="1.5"
            />
            <g transform="translate(38 38)" stroke="#ffffff" stroke-width="2.6" stroke-linejoin="round" fill="none">
              <!-- 左页 + 右页，中间一道书脊 -->
              <path d="M-16-13h13a3 3 0 0 1 3 3v23h-13a3 3 0 0 0-3 3z" />
              <path d="M16-13H3a3 3 0 0 0-3 3v23h13a3 3 0 0 1 3 3z" />
            </g>
          </g>

          <!-- 卡片 2：耳机（数码电子） -->
          <g transform="translate(30 168)">
            <rect width="76" height="76" rx="18" fill="#ffffff" fill-opacity="0.16" />
            <rect
              x="0.75"
              y="0.75"
              width="74.5"
              height="74.5"
              rx="17.25"
              fill="none"
              stroke="#ffffff"
              stroke-opacity="0.45"
              stroke-width="1.5"
            />
            <g transform="translate(38 38)" stroke="#ffffff" stroke-width="2.6" stroke-linecap="round" fill="none">
              <!-- 头梁 + 两个耳罩 -->
              <path d="M-15 4a15 15 0 0 1 30 0" />
              <rect x="-20" y="3" width="8" height="14" rx="4" fill="#ffffff" fill-opacity="0.9" stroke="none" />
              <rect x="12" y="3" width="8" height="14" rx="4" fill="#ffffff" fill-opacity="0.9" stroke="none" />
            </g>
          </g>

          <!-- 卡片 3：绿植（生活闲置） -->
          <g transform="translate(214 168)">
            <rect width="76" height="76" rx="18" fill="#ffffff" fill-opacity="0.16" />
            <rect
              x="0.75"
              y="0.75"
              width="74.5"
              height="74.5"
              rx="17.25"
              fill="none"
              stroke="#ffffff"
              stroke-opacity="0.45"
              stroke-width="1.5"
            />
            <g transform="translate(38 38)" stroke="#ffffff" stroke-width="2.6" stroke-linecap="round" fill="none">
              <!-- 花盆 + 两片叶子 -->
              <path d="M-11 4h22l-3 14h-16z" stroke-linejoin="round" />
              <path d="M0 4V-14" />
              <path d="M0-2c-9-1-11-11-6-15 6 0 8 8 6 15z" fill="#ffffff" fill-opacity="0.55" stroke="none" />
              <path d="M0-6c9-1 11-11 6-15-6 0-8 8-6 15z" fill="#ffffff" fill-opacity="0.85" stroke="none" />
            </g>
          </g>

          <!-- 点缀：小圆点 -->
          <circle cx="34" cy="72" r="5" fill="#ffffff" fill-opacity="0.5" />
          <circle cx="288" cy="96" r="4" fill="#ffffff" fill-opacity="0.4" />
        </svg>
      </div>

      <div class="brand-panel__copy">
        <h1 class="brand-panel__title">校园二手交易</h1>
        <p class="brand-panel__slogan">让闲置流动起来</p>

        <ul class="brand-panel__highlights">
          <li v-for="item in highlights" :key="item.title">
            <span class="brand-panel__dot" aria-hidden="true" />
            <span class="brand-panel__hl-title">{{ item.title }}</span>
            <span class="brand-panel__hl-desc">{{ item.desc }}</span>
          </li>
        </ul>
      </div>
    </div>
  </aside>
</template>

<style scoped lang="scss">
.brand-panel {
  position: relative;
  overflow: hidden;
  background: $cm-gradient-brand;
  color: #ffffff;
  padding: 48px 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;

  // 柔光斑
  &__blob {
    position: absolute;
    border-radius: 50%;
    filter: blur(2px);
    pointer-events: none;

    &--a {
      width: 260px;
      height: 260px;
      top: -80px;
      right: -60px;
      background: rgba(255, 255, 255, 0.14);
    }

    &--b {
      width: 200px;
      height: 200px;
      bottom: -70px;
      left: -50px;
      background: rgba(255, 255, 255, 0.1);
    }
  }

  &__inner {
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    gap: 26px;
  }

  &__art {
    width: 100%;
    max-width: 320px;
    align-self: center;
    // 轻微浮动动画：答辩演示时画面不至于完全静止（幅度很小，不喧宾夺主）
    animation: cm-float 6s ease-in-out infinite;
  }

  &__copy {
    text-align: left;
  }

  &__title {
    font-size: 30px;
    font-weight: 800;
    letter-spacing: 2px;
    margin-bottom: 6px;
  }

  &__slogan {
    font-size: 15px;
    letter-spacing: 4px;
    color: rgba(255, 255, 255, 0.9);
    margin-bottom: 26px;
  }

  &__highlights {
    display: flex;
    flex-direction: column;
    gap: 12px;

    li {
      display: grid;
      grid-template-columns: 14px auto;
      grid-template-rows: auto auto;
      column-gap: 8px;
      align-items: center;
    }
  }

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.85);
    grid-row: span 2;
    align-self: start;
    margin-top: 7px;
  }

  &__hl-title {
    font-size: 14px;
    font-weight: 600;
  }

  &__hl-desc {
    font-size: 12px;
    color: rgba(255, 255, 255, 0.78);
    grid-column: 2;
  }

  @include cm-max($cm-bp-md) {
    padding: 32px 28px;

    &__art {
      max-width: 220px;
    }

    &__title {
      font-size: 24px;
    }
  }
}

@keyframes cm-float {
  0%,
  100% {
    transform: translateY(0);
  }

  50% {
    transform: translateY(-8px);
  }
}
</style>
