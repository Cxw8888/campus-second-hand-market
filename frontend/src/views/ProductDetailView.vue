<script setup>
/**
 * 商品详情页 /product/:id
 *
 * 三个行为要点（与验收要求一一对应）：
 *   · 立即购买：未登录 → 提示「请先登录」并跳 /login（带 redirect 回跳）；已登录 → 跳 /order/create?productId=xxx
 *   · 加入收藏：未登录 → 跳登录页；已登录 → POST /favorite/{productId}
 *   · 收藏态：进入页面时（已登录）调 GET /favorite/check/{id} 回填「已收藏 / 加入收藏」
 *
 * 关于兜底：详情页本身没有 mock 需求，但如果首页正处于「接口报错 → mock 兜底」状态，
 * 用户点进来的 id 就是 mock-x，后端必然查不到。为了让演示链路不断，这里同样做了 mock 兜底。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Star, StarFilled, ShoppingCart, Location, Box, Van, Clock } from '@element-plus/icons-vue'
import ProductImage from '@/components/ProductImage.vue'
import ConditionTag from '@/components/ConditionTag.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { getProductDetail } from '@/api/product'
import { addFavorite, checkFavorite } from '@/api/favorite'
import { getOrderToken } from '@/api/order'
import { mockProductDetail } from '@/api/mock'
import { useUserStore } from '@/stores/user'
import { formatPrice, formatRelativeTime } from '@/utils/format'
import { productStatusLabel } from '@/utils/constants'

const props = defineProps({
  /** 路由参数 :id（字符串） */
  id: { type: String, required: true }
})

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(true)
const product = ref(null)
const usingMock = ref(false)

/** 收藏态：null=未查询（未登录或还在请求），true/false=已收藏/未收藏 */
const favorited = ref(false)
const favoriteLoading = ref(false)
/** 「立即购买」点击后正在获取防重 Token */
const buying = ref(false)

const images = computed(() => {
  const list = product.value?.imageUrls
  return Array.isArray(list) && list.length ? list : []
})

const price = computed(() => formatPrice(product.value?.price))
const conditionLevel = computed(() => product.value?.conditionLevel)
const statusLabel = computed(() => productStatusLabel(product.value?.status))

/** 库存告急：<=1 件时用橙色强调（对应设计规范里的「库存告急」语义） */
const lowStock = computed(() => {
  const stock = Number(product.value?.stock)
  return Number.isFinite(stock) && stock > 0 && stock <= 1
})

const soldOut = computed(() => Number(product.value?.stock) === 0 || Number(product.value?.status) === 2)

const seller = computed(() => ({
  name: product.value?.sellerNickname || '匿名同学',
  initial: (product.value?.sellerNickname || '同').trim().charAt(0),
  campus: product.value?.tradeLocation || '校区待约定'
}))

// ------------------------------------------------------------------ 拉详情
async function fetchDetail() {
  loading.value = true
  usingMock.value = false
  try {
    product.value = await getProductDetail(props.id, { silent: true })
  } catch (error) {
    console.warn('[detail] 商品详情接口异常，已降级为本地演示数据 ——', error?.message)
    product.value = mockProductDetail(props.id)
    usingMock.value = true
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------ 收藏
async function fetchFavoriteState() {
  if (!userStore.isLoggedIn) return
  try {
    const data = await checkFavorite(props.id, { silent: true })
    favorited.value = Boolean(data?.favorited)
  } catch (error) {
    console.warn('[detail] 查询收藏状态失败：', error?.message)
  }
}

/**
 * 加入收藏
 *
 * 按需求：收藏成功后按钮变成灰色「已收藏」并禁用（不提供在本页取消收藏）。
 * 未登录 → 提示后跳登录页，并把当前商品页塞进 redirect，登录完能跳回来。
 */
async function handleFavorite() {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (favorited.value) return // 已收藏：按钮本身也是 disabled，这里再兜一层

  favoriteLoading.value = true
  try {
    await addFavorite(props.id)
    favorited.value = true
    ElMessage.success('已加入收藏')
  } catch (error) {
    console.warn('[detail] 收藏失败：', error?.message)
  } finally {
    favoriteLoading.value = false
  }
}

// ------------------------------------------------------------------ 立即购买
/**
 * 立即购买
 *
 * 按需求：已登录时先调 GET /order/token 拿防重 Token，再带着它跳下单页。
 * 提前拿 Token 有两个好处：
 *   ① 顺带做一次「登录态是否真的有效」的校验 —— 401 会在这一步就跳登录页，
 *      而不是等用户填完表单点提交才失败；
 *   ② 下单页可以直接用这个 Token 提交，不用再等一次网络往返。
 * 下单页仍会在提交前判断 Token 是否临近过期（TTL 5 分钟）并自动换新，所以这里不怕用户慢慢填。
 */
async function handleBuy() {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }

  buying.value = true
  try {
    const data = await getOrderToken()
    router.push({
      name: 'order-create',
      query: { productId: props.id, token: data?.token || '' }
    })
  } catch (error) {
    console.warn('[detail] 获取下单 Token 失败：', error?.message)
  } finally {
    buying.value = false
  }
}

function goBack() {
  // 有历史就回退，没有（比如直接粘链接进来）就回首页
  if (window.history.length > 1) router.back()
  else router.push({ name: 'home' })
}

onMounted(async () => {
  await fetchDetail()
  await fetchFavoriteState()
})
</script>

<template>
  <main class="detail cm-container">
    <!-- 返回 + 面包屑 -->
    <div class="detail__crumb">
      <el-button link :icon="ArrowLeft" class="detail__back" @click="goBack">返回</el-button>
      <span class="detail__crumb-sep">/</span>
      <span class="detail__crumb-text">{{ product?.categoryName || '商品详情' }}</span>
    </div>

    <el-alert
      v-if="usingMock"
      class="detail__fallback"
      type="warning"
      show-icon
      :closable="false"
      title="后端未连接，当前展示的是本地演示数据"
    />

    <!-- 骨架 -->
    <div v-if="loading" class="detail__top">
      <el-skeleton animated>
        <template #template>
          <el-skeleton-item variant="image" style="width: 100%; height: 340px; border-radius: 12px" />
        </template>
      </el-skeleton>
      <el-skeleton :rows="6" animated />
    </div>

    <template v-else-if="product">
      <!-- ---------------- 上部：图集 + 信息 ---------------- -->
      <section class="detail__top">
        <div class="detail__gallery">
          <el-carousel
            v-if="images.length"
            :height="'340px'"
            :autoplay="images.length > 1"
            indicator-position="outside"
            trigger="click"
          >
            <el-carousel-item v-for="(img, index) in images" :key="index">
              <ProductImage :src="img" :alt="product.title" ratio="16 / 10" :icon-size="40" />
            </el-carousel-item>
          </el-carousel>

          <!-- 没有图集时，直接给一张大占位图（seed 数据就是这种情况） -->
          <ProductImage v-else :src="product.coverImage" :alt="product.title" ratio="16 / 10" :icon-size="48" />
        </div>

        <div class="detail__info">
          <div class="detail__tags">
            <ConditionTag :level="conditionLevel" size="md" />
            <span class="detail__status" :class="{ 'is-off': soldOut }">{{ statusLabel }}</span>
          </div>

          <h1 class="detail__title">{{ product.title }}</h1>

          <div class="detail__price-box">
            <span class="cm-price detail__price">
              <span class="cm-price__symbol">¥</span>{{ price }}
            </span>
            <span v-if="lowStock" class="detail__low-stock">
              <el-icon :size="13"><Clock /></el-icon>
              仅剩 {{ product.stock }} 件
            </span>
          </div>

          <dl class="detail__meta">
            <div class="detail__meta-row">
              <dt><el-icon :size="14"><Van /></el-icon>交易方式</dt>
              <!-- 交易方式三色标签：面交绿 / 邮寄蓝 / 皆可橙 -->
              <dd><TradeTypeTag :type="product.tradeType" size="sm" /></dd>
            </div>
            <div class="detail__meta-row">
              <dt><el-icon :size="14"><Location /></el-icon>面交地点</dt>
              <dd>{{ product.tradeLocation || '双方协商' }}</dd>
            </div>
            <div class="detail__meta-row">
              <dt><el-icon :size="14"><Box /></el-icon>剩余库存</dt>
              <dd :class="{ 'is-low': lowStock }">
                {{ product.stock }} 件
              </dd>
            </div>
            <div class="detail__meta-row">
              <dt><el-icon :size="14"><Clock /></el-icon>发布时间</dt>
              <dd>{{ formatRelativeTime(product.createTime) || '—' }}</dd>
            </div>
          </dl>
        </div>
      </section>

      <!-- ---------------- 中部：卖家信息 ---------------- -->
      <section class="detail__seller">
        <span class="detail__seller-avatar">{{ seller.initial }}</span>
        <div class="detail__seller-info">
          <div class="detail__seller-name">
            {{ seller.name }}
            <span class="detail__seller-badge">在校同学</span>
          </div>
          <div class="detail__seller-campus">
            <el-icon :size="12"><Location /></el-icon>
            {{ seller.campus }}
          </div>
        </div>
        <div class="detail__seller-tip">校内实地面交，建议在人多的地方交易</div>
      </section>

      <!-- ---------------- 中部：商品描述 ---------------- -->
      <section class="detail__desc">
        <h2 class="detail__section-title">商品描述</h2>
        <p class="detail__desc-text">{{ product.description || '卖家很懒，没有填写描述。' }}</p>
      </section>

      <!-- ---------------- 底部操作栏 ---------------- -->
      <div class="detail__bar">
        <!-- 加入收藏：绿色描边；收藏成功后变灰并禁用（按需求不提供本页取消） -->
        <el-button
          class="detail__favorite"
          :class="{ 'is-active': favorited }"
          size="large"
          :icon="favorited ? StarFilled : Star"
          :loading="favoriteLoading"
          :disabled="favorited"
          @click="handleFavorite"
        >
          {{ favorited ? '已收藏' : '加入收藏' }}
        </el-button>

        <el-button
          class="detail__buy"
          size="large"
          :disabled="soldOut"
          :loading="buying"
          :icon="ShoppingCart"
          @click="handleBuy"
        >
          {{ soldOut ? '已售罄' : '立即购买' }}
        </el-button>
      </div>
    </template>

    <!-- 详情也没拿到（理论上不会发生，因为兜底一定有数据） -->
    <el-empty v-else description="商品不存在或已下架" />
  </main>
</template>

<style scoped lang="scss">
.detail {
  flex: 1;
  padding-top: 16px;
  padding-bottom: 40px;

  &__crumb {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
    font-size: 13px;
    color: $cm-text-secondary;
  }

  &__back {
    color: $cm-text-secondary;

    &:hover {
      color: $cm-primary;
    }
  }

  &__crumb-sep {
    color: $cm-text-placeholder;
  }

  &__fallback {
    margin-bottom: 14px;
    border-radius: $cm-radius;
  }

  // ---------------- 上部两栏 ----------------
  &__top {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 420px;
    gap: 22px;
    align-items: start;
  }

  &__gallery {
    @include cm-card;
    overflow: hidden;
    padding: 0;

    :deep(.el-carousel__container) {
      border-radius: $cm-radius-card;
    }

    :deep(.el-carousel__indicators--outside) {
      margin-top: 10px;
    }
  }

  &__info {
    @include cm-card;
    padding: 22px;
  }

  &__tags {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
  }

  &__status {
    display: inline-flex;
    align-items: center;
    height: 28px;
    padding: 0 12px;
    border-radius: $cm-radius-pill;
    font-size: 13px;
    color: $cm-primary-700;
    background: $cm-primary-50;

    &.is-off {
      color: $cm-accent-dark;
      background: $cm-accent-50;
    }
  }

  &__title {
    font-size: 21px;
    font-weight: 700;
    line-height: 1.45;
    color: $cm-text;
    margin-bottom: 16px;
  }

  &__price-box {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    border-radius: $cm-radius;
    background: linear-gradient(135deg, $cm-accent-50 0%, #fffdf5 100%);
    border: 1px solid rgba($cm-accent, 0.22);
    margin-bottom: 18px;
  }

  &__price {
    @include cm-price(30px);
  }

  &__low-stock {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 10px;
    border-radius: $cm-radius-pill;
    font-size: 12px;
    font-weight: 600;
    color: #ffffff;
    background: $cm-accent;
  }

  &__meta {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__meta-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 11px 0;
    border-bottom: 1px dashed $cm-border-light;

    &:last-child {
      border-bottom: none;
    }

    dt {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      font-size: 13px;
      color: $cm-text-secondary;
      font-weight: 400;
    }

    dd {
      margin: 0;
      font-size: 13px;
      font-weight: 600;
      color: $cm-text;

      &.is-low {
        color: $cm-accent-dark;
      }
    }
  }

  // ---------------- 卖家卡片 ----------------
  &__seller {
    @include cm-card;
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 18px 20px;
    margin-top: 20px;
  }

  &__seller-avatar {
    @include cm-center;
    flex: none;
    width: 48px;
    height: 48px;
    border-radius: 50%;
    font-size: 19px;
    font-weight: 700;
    color: #ffffff;
    background: $cm-gradient-brand;
    box-shadow: 0 4px 12px rgba(16, 185, 129, 0.28);
  }

  &__seller-info {
    min-width: 0;
  }

  &__seller-name {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 600;
    color: $cm-text;
  }

  &__seller-badge {
    padding: 1px 8px;
    border-radius: $cm-radius-pill;
    font-size: 11px;
    font-weight: 500;
    color: $cm-primary-700;
    background: $cm-primary-50;
    border: 1px solid rgba($cm-primary, 0.22);
  }

  &__seller-campus {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-top: 3px;
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__seller-tip {
    margin-left: auto;
    flex: none;
    font-size: 12px;
    color: $cm-text-placeholder;
  }

  // ---------------- 描述 ----------------
  &__desc {
    @include cm-card;
    padding: 20px 22px;
    margin-top: 20px;
  }

  &__section-title {
    font-size: 15px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 12px;
    padding-left: 10px;
    border-left: 3px solid $cm-primary;
    line-height: 1.2;
  }

  &__desc-text {
    font-size: 14px;
    line-height: 1.9;
    color: $cm-text-secondary;
    white-space: pre-wrap;
  }

  // ---------------- 底部操作栏 ----------------
  &__bar {
    @include cm-card;
    position: sticky;
    bottom: 16px;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 14px;
    padding: 14px 18px;
    margin-top: 22px;
    box-shadow: 0 6px 24px rgba(17, 24, 39, 0.12);
  }

  // 收藏：绿色描边按钮
  &__favorite {
    height: 46px;
    padding: 0 22px;
    border-radius: $cm-radius;
    font-weight: 600;
    color: $cm-primary;
    background: #ffffff;
    border: 1.5px solid $cm-primary;

    &:hover,
    &:focus {
      color: $cm-primary-dark;
      background: $cm-primary-50;
      border-color: $cm-primary-dark;
    }

    // 已收藏：灰色 + 禁用，明确表达「收藏过了，不用再点」
    &.is-active,
    &.is-disabled {
      color: $cm-text-secondary;
      background: $cm-gray-tag-50;
      border-color: $cm-border;
      cursor: not-allowed;
    }
  }

  // 立即购买：全站唯一使用「橙→金」渐变的地方
  &__buy {
    height: 46px;
    min-width: 168px;
    padding: 0 26px;
    border: none;
    border-radius: $cm-radius;
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 2px;
    color: #ffffff;
    background: $cm-gradient-buy;
    box-shadow: $cm-shadow-buy;
    transition:
      transform 0.2s ease,
      box-shadow 0.2s ease,
      filter 0.2s ease;

    &:hover:not(.is-disabled) {
      transform: translateY(-2px);
      filter: saturate(1.08);
      box-shadow: 0 6px 18px rgba(245, 158, 11, 0.42);
    }

    &.is-disabled {
      background: #d1d5db;
      box-shadow: none;
      color: #ffffff;
    }
  }

  // ---------------- 响应式 ----------------
  @include cm-max($cm-bp-md) {
    &__top {
      grid-template-columns: minmax(0, 1fr);
    }

    &__seller-tip {
      display: none;
    }

    &__bar {
      justify-content: stretch;

      :deep(.el-button) {
        flex: 1;
      }
    }
  }
}
</style>
