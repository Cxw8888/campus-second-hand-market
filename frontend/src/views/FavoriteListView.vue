<script setup>
/**
 * 我的收藏 /favorite/list
 *
 * 数据源：GET /api/v1/favorite/list（**分页**，PageQuery：page / size）
 *
 * 后端语义（已读 FavoriteServiceImpl 核实）：
 *   · 列表**不过滤**失效商品，而是用自定义 SQL 绕过逻辑删除，把商品的当前状态一并返回
 *     （productStatus / isDeleted / available），供前端标注失效 —— 所以这里绝不能自己过滤掉
 *   · 收藏是**物理删除**（tb_favorite 无 is_deleted），取消收藏后该条直接消失
 *
 * 校园二手要点：失效商品**保留在列表里**（学生需要看到自己收藏过什么并主动清理），
 * 只是灰化 + 角标 + 不可下单。三种失效状态（已删除 / 已下架 / 已售罄）都要覆盖。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Star } from '@element-plus/icons-vue'
import FavoriteCard from '@/components/FavoriteCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getFavoriteList, removeFavorite } from '@/api/favorite'
import { FAVORITE_PAGE_SIZE, resolveFavoriteState } from '@/utils/constants'

const router = useRouter()

const loading = ref(true)
const records = ref([])
const total = ref(0)
const page = ref(1)

/** 同步锁：正在取消收藏的商品 id（空字符串表示空闲） */
const busyId = ref('')

const isEmpty = computed(() => !loading.value && records.value.length === 0)
const showPager = computed(() => total.value > FAVORITE_PAGE_SIZE)

/** 失效数量：给学生一个「该清理了」的直观提示 */
const invalidCount = computed(() => records.value.filter((it) => !resolveFavoriteState(it).orderable).length)

async function fetchList() {
  loading.value = true
  try {
    const data = await getFavoriteList({ page: page.value, size: FAVORITE_PAGE_SIZE })
    // total 等字段是 Long→String，比较前必须 Number()
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    console.warn('[favorite] 收藏列表加载失败：', error?.message)
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function goDetail(item) {
  router.push({ name: 'product-detail', params: { id: String(item.productId) } })
}

function goHome() {
  router.push({ name: 'home' })
}

/**
 * 取消收藏
 *
 * 同步锁必须在任何 await 之前置位 —— 否则连点会发出多次请求
 * （下单页当初就是这个坑，见 OrderCreateView.spec.js）。
 */
async function handleCancel(item) {
  if (busyId.value) return
  busyId.value = String(item.productId)

  try {
    await removeFavorite(item.productId)

    // 本地移除，不等重新拉列表（体感更快）；总数同步减 1
    records.value = records.value.filter((it) => String(it.productId) !== String(item.productId))
    total.value = Math.max(0, total.value - 1)
    ElMessage.success('已取消收藏')

    // 当前页被清空且还有上一页 → 回退一页，避免停在空白页
    if (records.value.length === 0 && page.value > 1) {
      page.value -= 1
      await fetchList()
    }
  } catch (error) {
    console.warn('[favorite] 取消收藏失败：', error?.message)
    // 业务错误已由 axios 拦截器提示；这里兜底「没有提示过」的情况
    if (!error?.code) ElMessage.error(error?.message || '取消收藏失败，请稍后重试')
  } finally {
    busyId.value = ''
  }
}

function handlePageChange(next) {
  page.value = next
  fetchList()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(fetchList)
</script>

<template>
  <main class="favorite cm-container">
    <header class="favorite__header">
      <div>
        <h1 class="favorite__title">我的收藏</h1>
        <p class="favorite__sub">
          收藏的闲置物品会一直留在这里（含已失效的），方便你随时清理
        </p>
      </div>
      <div v-if="!loading && total > 0" class="favorite__stats">
        <span class="favorite__stat">
          共 <b class="cm-num">{{ total }}</b> 件
        </span>
        <span v-if="invalidCount > 0" class="favorite__stat is-warn">
          本页有 <b class="cm-num">{{ invalidCount }}</b> 件已失效
        </span>
      </div>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="favorite__grid">
      <div v-for="n in 6" :key="n" class="favorite__skeleton">
        <el-skeleton animated>
          <template #template>
            <el-skeleton-item variant="image" style="width: 100%; height: 150px" />
            <div style="padding: 12px 14px">
              <el-skeleton-item variant="text" style="width: 80%" />
              <el-skeleton-item variant="text" style="width: 45%; margin-top: 10px" />
            </div>
          </template>
        </el-skeleton>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState
      v-else-if="isEmpty"
      title="还没有收藏商品，去逛逛吧"
      description="看到喜欢的闲置，点一下「加入收藏」就会出现在这里，方便下次快速找到。"
      action-text="去逛首页"
      @action="goHome"
    />

    <!-- 列表 -->
    <template v-else>
      <div class="favorite__grid">
        <FavoriteCard
          v-for="item in records"
          :key="String(item.productId)"
          :item="item"
          :busy="busyId === String(item.productId)"
          @open="goDetail"
          @cancel="handleCancel"
        />
      </div>

      <div v-if="showPager" class="favorite__pager">
        <el-pagination
          background
          layout="prev, pager, next, jumper, total"
          :total="total"
          :page-size="FAVORITE_PAGE_SIZE"
          :current-page="page"
          @current-change="handlePageChange"
        />
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
.favorite {
  flex: 1;
  padding-top: 24px;
  padding-bottom: 56px;

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

  &__stats {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: none;
  }

  &__stat {
    padding: 4px 12px;
    border-radius: $cm-radius-pill;
    font-size: 12px;
    color: $cm-text-secondary;
    background: $cm-hover-bg;

    b {
      color: $cm-primary;
      font-size: 14px;
    }

    // 失效提醒用暖橙，与「售罄/库存告急」共用同一套强调色语义
    &.is-warn {
      color: $cm-accent-dark;
      background: $cm-accent-50;

      b {
        color: $cm-accent-dark;
      }
    }
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 18px;
  }

  &__skeleton {
    @include cm-card;
    overflow: hidden;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 28px;
  }

  @include cm-max($cm-bp-lg) {
    &__grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @include cm-max($cm-bp-sm) {
    &__header {
      flex-direction: column;
      align-items: flex-start;
    }

    &__grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }
}
</style>
