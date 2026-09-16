<script setup>
/**
 * 消息中心 /notification/list
 *
 * 数据源（已读 NotificationController 核实）：
 *   GET  /notification/list?page=&size=&isRead=（可选）
 *   PUT  /notification/read/{id}      标记单条（越权 203）
 *   PUT  /notification/read-all       全部标记
 *   GET  /notification/unread-count   未读数（全局轮询见 stores/notification.js）
 *
 * 校园二手语义：消息中心是**待办入口**而不是纯展示 ——
 *   订单通知（type=1，bizId=订单ID）点进去要能到订单详情；
 *   审核通知（type=2，bizId=商品ID）点进去要能到商品详情；
 *   bizId=0 表示没有关联业务对象（例如封禁通知），只标已读不跳转。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check } from '@element-plus/icons-vue'
import NotificationItem from '@/components/NotificationItem.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getNotificationList, markAllNotificationsRead, markNotificationRead } from '@/api/notification'
import { useNotificationStore } from '@/stores/notification'
import { NOTIFICATION_PAGE_SIZE, resolveNotificationTarget } from '@/utils/constants'

const router = useRouter()
const notificationStore = useNotificationStore()

const loading = ref(true)
const records = ref([])
const total = ref(0)
const page = ref(1)
/** 同步锁：正在「全部标记已读」 */
const markingAll = ref(false)

const isEmpty = computed(() => !loading.value && records.value.length === 0)
const showPager = computed(() => total.value > NOTIFICATION_PAGE_SIZE)
/** 未读数直接读全局 store，保证与导航栏角标永远一致 */
const unreadCount = computed(() => notificationStore.unreadCount)

async function fetchList() {
  loading.value = true
  try {
    const data = await getNotificationList({ page: page.value, size: NOTIFICATION_PAGE_SIZE })
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    console.warn('[notification] 列表加载失败：', error?.message)
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * 点击一条消息
 *
 * 顺序：本地先更新（立刻有反馈）→ 后台静默标已读 → 再跳转。
 * 已读接口失败**不弹错也不阻断跳转**：它是后台行为，用户真正的诉求是"去看订单/商品"。
 */
async function handleOpen(item) {
  const wasUnread = Number(item.isRead) === 0
  const target = resolveNotificationTarget(item)

  if (wasUnread) {
    // ① 本地乐观更新：列表项标为已读 + 全局未读数减 1
    item.isRead = 1
    notificationStore.decrement(1)
    // ② 后台静默标记（不 await，避免拖慢跳转；失败只记日志）
    markNotificationRead(item.id).catch((error) => {
      console.warn('[notification] 标记已读失败（已静默）：', error?.message)
    })
  }

  // ③ 有业务对象才跳转
  if (target) router.push(target)
}

/** 全部标记已读（用户主动操作 → 加同步锁 + 失败要提示） */
async function handleMarkAll() {
  if (markingAll.value) return
  if (unreadCount.value === 0) {
    ElMessage.info('没有未读消息')
    return
  }
  markingAll.value = true
  try {
    await markAllNotificationsRead()
    // 本地全部置为已读 + 角标清零，不必等重新拉列表
    records.value.forEach((item) => {
      item.isRead = 1
    })
    notificationStore.clear()
    ElMessage.success('已全部标记为已读')
  } catch (error) {
    console.warn('[notification] 全部标记已读失败：', error?.message)
  } finally {
    markingAll.value = false
  }
}

function handlePageChange(next) {
  page.value = next
  fetchList()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function goHome() {
  router.push({ name: 'home' })
}

onMounted(() => {
  fetchList()
  // 进页面立即校准一次未读数（轮询是 30 秒一次，可能刚被别处改过）
  notificationStore.refresh()
})
</script>

<template>
  <main class="notice cm-container">
    <header class="notice__header">
      <div>
        <h1 class="notice__title">消息中心</h1>
        <p class="notice__sub">订单进度、商品审核结果都会在这里通知你</p>
      </div>

      <div class="notice__actions">
        <span class="notice__unread" :class="{ 'is-zero': unreadCount === 0 }">
          <template v-if="unreadCount > 0">
            <b class="cm-num">{{ unreadCount }}</b> 条未读
          </template>
          <template v-else>全部已读</template>
        </span>

        <el-button
          type="primary"
          round
          :icon="Check"
          :loading="markingAll"
          :disabled="markingAll || unreadCount === 0"
          @click="handleMarkAll"
        >
          全部标记已读
        </el-button>
      </div>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="notice__list">
      <div v-for="n in 4" :key="n" class="notice__skeleton">
        <el-skeleton animated>
          <template #template>
            <div style="display: flex; gap: 12px; padding: 16px">
              <el-skeleton-item variant="circle" style="width: 38px; height: 38px" />
              <div style="flex: 1">
                <el-skeleton-item variant="text" style="width: 40%" />
                <el-skeleton-item variant="text" style="width: 75%; margin-top: 12px" />
              </div>
            </div>
          </template>
        </el-skeleton>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState
      v-else-if="isEmpty"
      title="暂无消息"
      description="下单、支付、发货、审核结果等动态会以站内信的形式通知你，有消息时会在这里出现。"
      action-text="去逛首页"
      @action="goHome"
    />

    <!-- 列表 -->
    <template v-else>
      <div class="notice__list">
        <NotificationItem v-for="item in records" :key="String(item.id)" :item="item" @open="handleOpen" />
      </div>

      <div v-if="showPager" class="notice__pager">
        <el-pagination
          background
          layout="prev, pager, next, jumper, total"
          :total="total"
          :page-size="NOTIFICATION_PAGE_SIZE"
          :current-page="page"
          @current-change="handlePageChange"
        />
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
.notice {
  flex: 1;
  padding-top: 24px;
  padding-bottom: 56px;
  max-width: 860px;

  &__header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 18px;
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

  &__actions {
    display: flex;
    align-items: center;
    gap: 14px;
    flex: none;
  }

  &__unread {
    font-size: 13px;
    color: $cm-accent-dark;

    b {
      font-size: 16px;
      font-weight: 800;
    }

    &.is-zero {
      color: $cm-text-placeholder;
    }
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__skeleton {
    @include cm-card;
    overflow: hidden;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 26px;
  }

  @include cm-max($cm-bp-sm) {
    &__header {
      flex-direction: column;
      align-items: flex-start;
    }
  }
}
</style>
