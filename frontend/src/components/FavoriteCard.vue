<script setup>
/**
 * 收藏项卡片
 *
 * 校园二手特征：**库存常为 1，售罄是高频状态**。所以失效判定必须覆盖三种情况，
 * 而且失效项**不隐藏**（学生需要看到自己收藏过什么，并主动清理）。
 *
 *   已失效(isDeleted=1) → 灰化 + 「已失效」角标 + 不可下单
 *   已下架(status=0)    → 灰化 + 「已下架」角标 + 不可下单
 *   已售罄(status=2)    → 灰化 + 「已售罄」角标 + 不可下单
 *   在售(status=1)      → 正常展示 + 可点进详情
 *
 * 「能否下单」以后端下发的 `available` 为准（后端已按 status=1 && !isDeleted 算好），
 * 状态文案与色调由 constants.js 的 resolveFavoriteState 统一给出 —— 不在这里硬编码。
 */
import { computed } from 'vue'
import ProductImage from '@/components/ProductImage.vue'
import { formatPrice, formatRelativeTime } from '@/utils/format'
import { resolveFavoriteState } from '@/utils/constants'

const props = defineProps({
  /** 后端 FavoriteVO */
  item: { type: Object, required: true },
  /** 该卡片是否有操作正在进行（父组件用它做同步锁） */
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['open', 'cancel'])

const state = computed(() => resolveFavoriteState(props.item))
const price = computed(() => formatPrice(props.item.price))
const savedAt = computed(() => formatRelativeTime(props.item.createTime))
/** 失效项直接标灰 */
const invalid = computed(() => !state.value.orderable)

function handleOpen() {
  // 失效商品不跳详情：详情页对已删除商品会直接 204，对已下架/售罄也没有下单入口，
  // 与其把用户送进死胡同，不如留在列表里让他顺手取消收藏。
  if (invalid.value) return
  emit('open', props.item)
}
</script>

<template>
  <article
    class="favorite-card"
    :class="{ 'is-invalid': invalid, 'is-busy': busy }"
    @click="handleOpen"
  >
    <div class="favorite-card__cover">
      <ProductImage :src="item.coverImage" :alt="item.title" ratio="4 / 3" />
      <!-- 失效角标：三种失效状态共用同一位置，文案由 resolveFavoriteState 给出 -->
      <span v-if="invalid" class="favorite-card__badge" :class="`is-${state.tone}`">
        {{ state.label }}
      </span>
      <span v-else class="favorite-card__badge is-green">{{ state.label }}</span>
    </div>

    <div class="favorite-card__body">
      <h3 class="favorite-card__title">{{ item.title }}</h3>

      <div class="favorite-card__price-row">
        <span class="cm-price">
          <span class="cm-price__symbol">¥</span>{{ price }}
        </span>
        <span v-if="savedAt" class="favorite-card__time">{{ savedAt }}收藏</span>
      </div>

      <!-- 失效提示：说明为什么点不动，避免用户以为页面坏了 -->
      <p v-if="invalid" class="favorite-card__hint">
        该商品{{ state.label }}，已无法下单，可取消收藏清理列表
      </p>

      <div class="favorite-card__actions" @click.stop>
        <el-button
          v-if="!invalid"
          size="small"
          round
          type="primary"
          plain
          :disabled="busy"
          @click="emit('open', item)"
        >
          查看详情
        </el-button>

        <el-button
          size="small"
          round
          type="danger"
          plain
          :loading="busy"
          :disabled="busy"
          @click="emit('cancel', item)"
        >
          取消收藏
        </el-button>
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
.favorite-card {
  @include cm-card;
  @include cm-hover-lift(-4px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  cursor: pointer;

  // 失效项：灰化 + 取消 hover 上浮（它本来就不该引导点击）
  &.is-invalid {
    cursor: default;
    filter: grayscale(0.7);
    opacity: 0.9;

    &:hover {
      transform: none;
      box-shadow: $cm-shadow-card;
    }
  }

  &.is-busy {
    opacity: 0.7;
  }

  &__cover {
    position: relative;
  }

  &__badge {
    position: absolute;
    right: 10px;
    top: 10px;
    padding: 2px 10px;
    border-radius: $cm-radius-pill;
    font-size: 11px;
    font-weight: 600;
    color: #ffffff;

    &.is-green {
      background: $cm-primary;
    }

    &.is-orange {
      background: $cm-accent;
    }

    &.is-gray {
      background: $cm-gray-tag;
    }

    &.is-blue {
      background: $cm-blue;
    }
  }

  &__body {
    padding: 12px 14px 14px;
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
  }

  &__title {
    font-size: 14px;
    font-weight: 600;
    line-height: 1.45;
    color: $cm-text;
    min-height: 40px;
    @include cm-ellipsis-lines(2);
  }

  &__price-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;

    .cm-price {
      @include cm-price(20px);
    }
  }

  &__time {
    font-size: 11px;
    color: $cm-text-placeholder;
    flex: none;
  }

  &__hint {
    font-size: 11px;
    line-height: 1.6;
    color: $cm-text-placeholder;
  }

  &__actions {
    display: flex;
    gap: 8px;
    margin-top: auto;
    padding-top: 10px;
    border-top: 1px solid $cm-border-light;

    :deep(.el-button) {
      margin-left: 0;
      flex: 1;
    }
  }
}
</style>
