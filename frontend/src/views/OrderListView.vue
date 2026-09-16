<script setup>
/**
 * 订单列表 /order/list?role=buyer|seller
 *
 * 数据源：GET /api/v1/order/list?role=buyer 或 ?role=seller（每页 10 条）
 *   buyer  → 我买到的（后端按 user_id 过滤）
 *   seller → 我卖出的（后端按 seller_id 过滤）
 *
 * 视角写进 URL query，好处：刷新不丢、可以直接分享链接、浏览器前进后退也正常。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Document } from '@element-plus/icons-vue'
import OrderCard from '@/components/OrderCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getOrderList } from '@/api/order'
import { ORDER_PAGE_SIZE } from '@/utils/constants'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const records = ref([])
const total = ref(0)
const page = ref(1)

/** 当前视角，直接由 URL 决定，避免出现「组件状态和地址栏不一致」 */
const role = computed(() => (route.query.role === 'seller' ? 'seller' : 'buyer'))

const isEmpty = computed(() => !loading.value && records.value.length === 0)
const showPager = computed(() => total.value > ORDER_PAGE_SIZE)

// ------------------------------------------------------------------ Tab 指示条
/**
 * 指示条位置用「实测 offsetLeft/offsetWidth」而不是写死的像素值：
 * 中文字宽随字体/字号变化，写死会在换字体时错位。
 */
const tabRefs = ref([])
const indicatorStyle = ref({ transform: 'translateX(0px)', width: '0px' })

function updateIndicator() {
  const index = role.value === 'seller' ? 1 : 0
  const el = tabRefs.value[index]
  if (!el) return
  indicatorStyle.value = {
    transform: `translateX(${el.offsetLeft}px)`,
    width: `${el.offsetWidth}px`
  }
}

function handleResize() {
  updateIndicator()
}

async function fetchList() {
  loading.value = true
  try {
    const data = await getOrderList(role.value, { page: page.value })
    // total / pages 等被后端 Long→String 序列化，比较前必须 Number()
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    console.warn('[order-list] 订单列表加载失败：', error?.message)
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 切换视角：换 URL 交给 watch 触发重新加载 */
function switchRole(next) {
  if (next === role.value) return
  page.value = 1
  router.replace({ name: 'order-list', query: { role: next } })
}

function handlePageChange(next) {
  page.value = next
  fetchList()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function goHome() {
  router.push({ name: 'home' })
}

// 视角变化（含浏览器前进/后退）都要重新拉数据，并同步指示条位置
watch(role, () => {
  page.value = 1
  fetchList()
  nextTick(updateIndicator)
})

onMounted(() => {
  fetchList()
  nextTick(updateIndicator)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<template>
  <main class="order-list cm-container">
    <!-- 页头 + Tab -->
    <header class="order-list__header">
      <div>
        <h1 class="order-list__title">我的订单</h1>
        <p class="order-list__sub">
          {{ role === 'buyer' ? '这里是你买到的所有订单' : '这里是你卖出的所有订单' }}
        </p>
      </div>

      <div class="order-list__tabs" role="tablist">
        <button
          ref="tabRefs"
          class="order-list__tab"
          :class="{ 'is-active': role === 'buyer' }"
          role="tab"
          :aria-selected="role === 'buyer'"
          @click="switchRole('buyer')"
        >
          我买到的
        </button>
        <button
          ref="tabRefs"
          class="order-list__tab"
          :class="{ 'is-active': role === 'seller' }"
          role="tab"
          :aria-selected="role === 'seller'"
          @click="switchRole('seller')"
        >
          我卖出的
        </button>
        <span class="order-list__tab-indicator" :style="indicatorStyle" />
      </div>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="order-list__skeleton">
      <div v-for="n in 4" :key="n" class="order-list__skeleton-item">
        <el-skeleton animated>
          <template #template>
            <div style="display: flex; gap: 16px; padding: 16px">
              <el-skeleton-item variant="image" style="width: 92px; height: 92px; border-radius: 8px" />
              <div style="flex: 1">
                <el-skeleton-item variant="text" style="width: 60%" />
                <el-skeleton-item variant="text" style="width: 40%; margin-top: 12px" />
                <el-skeleton-item variant="text" style="width: 30%; margin-top: 12px" />
              </div>
            </div>
          </template>
        </el-skeleton>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState
      v-else-if="isEmpty"
      :title="role === 'buyer' ? '还没有订单，去逛逛吧' : '还没有卖出的订单'"
      :description="
        role === 'buyer'
          ? '看中喜欢的闲置就下单吧，校内面交很方便。'
          : '你发布的商品被买走后，订单会出现在这里。'
      "
      action-text="去逛首页"
      @action="goHome"
    />

    <!-- 列表 -->
    <template v-else>
      <div class="order-list__count">
        <el-icon :size="13"><Document /></el-icon>
        共 <b class="cm-num">{{ total }}</b> 条订单
      </div>

      <div class="order-list__items">
        <OrderCard v-for="item in records" :key="item.id" :order="item" :role="role" />
      </div>

      <div v-if="showPager" class="order-list__pager">
        <el-pagination
          background
          layout="prev, pager, next, jumper, total"
          :total="total"
          :page-size="ORDER_PAGE_SIZE"
          :current-page="page"
          @current-change="handlePageChange"
        />
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
.order-list {
  flex: 1;
  padding-top: 24px;
  padding-bottom: 56px;
  max-width: 900px;

  &__header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 20px;
  }

  &__title {
    font-size: 22px;
    font-weight: 800;
    color: $cm-text;
    margin-bottom: 4px;
  }

  &__sub {
    font-size: 13px;
    color: $cm-text-secondary;
  }

  // ---------------- Tab ----------------
  &__tabs {
    position: relative;
    display: flex;
    gap: 24px;
    border-bottom: 1px solid $cm-border;
  }

  &__tab {
    padding: 0 2px 10px;
    border: none;
    background: transparent;
    font-size: 15px;
    font-weight: 600;
    color: $cm-text-placeholder;
    cursor: pointer;
    transition: color 0.2s ease;
    white-space: nowrap;

    &:hover {
      color: $cm-text-secondary;
    }

    &.is-active {
      color: $cm-primary;
    }
  }

  &__tab-indicator {
    position: absolute;
    bottom: -1px;
    left: 0;
    height: 2.5px;
    border-radius: 2px;
    background: $cm-primary;
    // 位置与宽度由 JS 实测写入（见 updateIndicator），这里只负责过渡动画
    transition:
      transform 0.24s cubic-bezier(0.4, 0, 0.2, 1),
      width 0.24s cubic-bezier(0.4, 0, 0.2, 1);
  }

  // ---------------- 列表 ----------------
  &__count {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 14px;
    font-size: 13px;
    color: $cm-text-secondary;

    b {
      color: $cm-primary;
      font-size: 15px;
    }
  }

  &__items {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  &__skeleton {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  &__skeleton-item {
    @include cm-card;
    overflow: hidden;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 28px;
  }

  @include cm-max($cm-bp-sm) {
    &__header {
      flex-direction: column;
      align-items: flex-start;
    }
  }
}
</style>
