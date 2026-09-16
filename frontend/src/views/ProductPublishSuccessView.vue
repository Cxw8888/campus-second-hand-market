<script setup>
/**
 * 发布成功页 /product/publish-success/:productId
 *
 * 定位是「给用户一个明确的下一步」：告诉他要等审核、大概多久、以及接下来能去哪。
 * productId 只用来展示与后续跳转，全程按字符串处理。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Clock } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const productId = computed(() => String(route.params.productId || ''))

function goMyProducts() {
  // 发布成功的商品是「待审核」，直接落到待审核 Tab 更顺手
  router.push({ name: 'product-my', query: { tab: 'pending' } })
}

function goPublishAgain() {
  router.push({ name: 'product-publish' })
}

function goHome() {
  router.push({ name: 'home' })
}
</script>

<template>
  <main class="publish-success cm-container">
    <section class="publish-success__card">
      <!-- 大绿勾：与下单成功页同一套视觉 -->
      <div class="publish-success__icon">
        <svg viewBox="0 0 96 96" width="96" height="96" aria-hidden="true">
          <circle cx="48" cy="48" r="42" fill="#ECFDF5" stroke="#10B981" stroke-width="3" />
          <path
            d="M30 49.5l12.5 12.5L67 36.5"
            fill="none"
            stroke="#10B981"
            stroke-width="6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </div>

      <h1 class="publish-success__heading">发布成功，等待审核</h1>
      <p class="publish-success__sub">管理员审核通过后，商品将在首页展示</p>

      <div v-if="productId" class="publish-success__id">
        商品编号 <b class="cm-num">{{ productId }}</b>
      </div>

      <div class="publish-success__actions">
        <el-button size="large" round type="primary" @click="goMyProducts">查看我的商品</el-button>
        <el-button size="large" round plain @click="goPublishAgain">继续发布</el-button>
      </div>

      <div class="publish-success__footer">
        <el-icon :size="13"><Clock /></el-icon>
        通常在 24 小时内完成审核
        <el-button link class="publish-success__home" @click="goHome">先去首页逛逛</el-button>
      </div>
    </section>
  </main>
</template>

<style scoped lang="scss">
.publish-success {
  flex: 1;
  padding-top: 48px;
  padding-bottom: 56px;
  max-width: 600px;

  &__card {
    @include cm-card;
    padding: 44px 36px 34px;
    text-align: center;
  }

  &__icon {
    display: flex;
    justify-content: center;
    margin-bottom: 18px;
    animation: cm-pop 0.42s cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  &__heading {
    font-size: 24px;
    font-weight: 800;
    color: $cm-text;
    margin-bottom: 10px;
  }

  &__sub {
    font-size: 14px;
    color: $cm-text-secondary;
    line-height: 1.7;
    margin-bottom: 16px;
  }

  &__id {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 14px;
    margin-bottom: 26px;
    border-radius: $cm-radius-pill;
    font-size: 12px;
    color: $cm-primary-700;
    background: $cm-primary-50;
    border: 1px solid rgba($cm-primary, 0.24);

    b {
      letter-spacing: 0.4px;
    }
  }

  &__actions {
    display: flex;
    justify-content: center;
    gap: 12px;
    margin-bottom: 26px;
  }

  &__footer {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding-top: 18px;
    border-top: 1px dashed $cm-border;
    font-size: 12px;
    color: $cm-text-placeholder;
  }

  &__home {
    margin-left: 6px;
    font-size: 12px;
    color: $cm-primary;

    &:hover {
      text-decoration: underline;
    }
  }
}

@keyframes cm-pop {
  0% {
    transform: scale(0.6);
    opacity: 0;
  }

  100% {
    transform: scale(1);
    opacity: 1;
  }
}
</style>
