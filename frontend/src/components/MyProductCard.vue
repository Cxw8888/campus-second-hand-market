<script setup>
/**
 * 「我的商品」列表项
 *
 * 操作按钮按商品状态给（与后端 ProductServiceImpl.resolveStatus 的状态机对齐）：
 *   1-上架中 → 编辑 / 下架（1→0）/ 删除
 *   3-待审核 → 编辑 / 撤回 / 删除
 *   0-已下架 → 编辑 / 重新上架 / 删除
 *   2-已售罄 → 编辑 / 删除
 *
 * ⚠️ 关于「撤回」：后端**没有**把 3-待审核 改回 0-已下架 的接口
 *    （PUT /product/{id} 只会把它重置成 3；PUT /product/off-shelf/{id} 只允许 1→0，否则 209）。
 *    所以这里做成禁用按钮 + 悬浮说明，而不是塞一个点了必然报错的假按钮。
 *    等后端补上「撤回」接口，把 :disabled 去掉、接上对应请求即可。
 */
import { computed } from 'vue'
import ProductImage from '@/components/ProductImage.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { formatPrice, formatRelativeTime } from '@/utils/format'
import { productStatusLabel, productStatusTone } from '@/utils/constants'

const props = defineProps({
  /** ProductListVO（来自 GET /product/my） */
  product: { type: Object, required: true },
  /** 该行是否有操作正在进行（父组件控制，用于禁用按钮防重复点击） */
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['edit', 'off-shelf', 're-list', 'delete'])

const status = computed(() => Number(props.product.status))
const price = computed(() => formatPrice(props.product.price))
const tone = computed(() => productStatusTone(status.value))
const statusLabel = computed(() => productStatusLabel(status.value))
const createdText = computed(() => formatRelativeTime(props.product.createTime))

const isOnSale = computed(() => status.value === 1)
const isPending = computed(() => status.value === 3)
const isOffShelf = computed(() => status.value === 0)
const isSoldOut = computed(() => status.value === 2)
</script>

<template>
  <article class="my-product" :class="{ 'is-busy': busy }">
    <!-- 封面：商品已被删除时后端返回空，走占位兜底 -->
    <div class="my-product__cover">
      <ProductImage :src="product.coverImage" :alt="product.title" ratio="1 / 1" :icon-size="22" />
    </div>

    <!-- 主信息 -->
    <div class="my-product__main">
      <h3 class="my-product__title">{{ product.title }}</h3>

      <div class="my-product__tags">
        <span class="my-product__status" :class="`is-${tone}`">{{ statusLabel }}</span>
        <!-- 交易方式三色标签：面交绿 / 邮寄蓝 / 皆可橙 -->
        <TradeTypeTag :type="product.tradeType" size="sm" />
        <span v-if="product.categoryName" class="my-product__category">{{ product.categoryName }}</span>
      </div>

      <div class="my-product__meta">
        <span>库存 {{ product.stock }} 件</span>
        <span v-if="createdText">发布于 {{ createdText }}</span>
      </div>
    </div>

    <!-- 价格 -->
    <div class="my-product__price">
      <span class="cm-price">
        <span class="cm-price__symbol">¥</span>{{ price }}
      </span>
    </div>

    <!-- 操作区 -->
    <div class="my-product__actions">
      <el-button size="small" round :disabled="busy" @click="emit('edit', product)">编辑</el-button>

      <el-button
        v-if="isOnSale"
        size="small"
        round
        plain
        type="warning"
        :disabled="busy"
        @click="emit('off-shelf', product)"
      >
        下架
      </el-button>

      <!-- 撤回：后端暂无接口，禁用并说明原因，避免给出一个必然失败的按钮 -->
      <el-tooltip
        v-if="isPending"
        content="后端暂未提供「撤回待审核商品」接口，可先删除或等待审核结果"
        placement="top"
      >
        <span class="my-product__disabled-wrap">
          <el-button size="small" round plain disabled>撤回</el-button>
        </span>
      </el-tooltip>

      <el-button
        v-if="isOffShelf"
        size="small"
        round
        plain
        type="primary"
        :disabled="busy"
        @click="emit('re-list', product)"
      >
        重新上架
      </el-button>

      <el-button size="small" round plain type="danger" :disabled="busy" @click="emit('delete', product)">
        删除
      </el-button>
    </div>
  </article>
</template>

<style scoped lang="scss">
.my-product {
  @include cm-card;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;

  &.is-busy {
    opacity: 0.7;
  }

  &__cover {
    flex: none;
    width: 88px;
    border-radius: $cm-radius;
    overflow: hidden;
  }

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: $cm-text;
    line-height: 1.45;
    @include cm-ellipsis-lines(2);
  }

  &__tags {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  // 状态标签配色与订单状态保持同一套语义
  &__status {
    padding: 2px 9px;
    border-radius: $cm-radius-pill;
    font-size: 11px;
    font-weight: 600;
    border: 1px solid transparent;

    &.is-green {
      color: $cm-primary-700;
      background: $cm-primary-50;
      border-color: rgba($cm-primary, 0.28);
    }

    &.is-blue {
      color: #1d4ed8;
      background: $cm-blue-50;
      border-color: rgba($cm-blue, 0.28);
    }

    &.is-orange {
      color: $cm-accent-dark;
      background: $cm-accent-50;
      border-color: rgba($cm-accent, 0.32);
    }

    &.is-gray {
      color: $cm-gray-tag;
      background: $cm-gray-tag-50;
      border-color: rgba($cm-gray-tag, 0.22);
    }
  }

  // 交易方式标签样式由 TradeTypeTag 提供（原来是中性灰，看不出面交/邮寄）
  &__category {
    padding: 2px 9px;
    border-radius: $cm-radius-pill;
    font-size: 11px;
    color: $cm-text-secondary;
    background: $cm-hover-bg;
  }

  &__meta {
    display: flex;
    gap: 14px;
    font-size: 12px;
    color: $cm-text-placeholder;
  }

  &__price {
    flex: none;
    min-width: 104px;
    text-align: right;
  }

  &__actions {
    flex: none;
    display: flex;
    flex-direction: column;
    gap: 8px;
    align-items: stretch;

    :deep(.el-button) {
      margin-left: 0;
    }
  }

  &__disabled-wrap {
    display: inline-block;
    cursor: not-allowed;
  }

  @include cm-max($cm-bp-md) {
    flex-wrap: wrap;

    &__actions {
      flex-direction: row;
      flex-wrap: wrap;
      width: 100%;
    }
  }
}
</style>
