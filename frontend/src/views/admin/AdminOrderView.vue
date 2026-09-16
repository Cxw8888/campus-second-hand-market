<script setup>
/**
 * 管理端 · 订单管理（/admin/order）
 *
 * 数据源（已读源码核实）
 *   · 列表：GET /api/v1/admin/order/list（AdminController.java:92-96）
 *       - status **没有默认值** → 省略即「全部」（AdminOrderQuery.java:18-19），所以可以有「全部」页签
 *       - orderNo 是 **精确匹配**（AdminServiceImpl.java:241 用的是 .eq 不是 .like）→ UI 文案写"精确查询"
 *       - 按 createTime 降序
 *   · 解冻：**PUT** /api/v1/admin/order/unfreeze/{id}，body `{target:'CANCEL'|'COMPLETE'}`（**必须大写**，小写直接 100）
 *       CANCEL → 5→4 已取消（并同步回补库存）；COMPLETE → 5→3 已完成（**不回补**）
 *   · 强制退款：**PUT** /api/v1/admin/order/force-refund/{id}?reason=xxx
 *       —— reason 是 **query 参数**（AdminController.java:107-108），写成 body 后端收不到；
 *       6/7→4，并**同步回补库存** + 写审计 + 通知买家
 *
 * ⚠️ 后端两类"失败"长得完全不同，必须分开处理：
 *   · code=209 / 204 / 203 → 列表数据过期（别处刚处理过）→ 静默刷新 + info，不弹"操作失败"
 *   · `code=200 + msg=请勿重复操作`（AdminServiceImpl.java:288-290）→ 那是**成功码**，
 *     拦截器直接 resolve，组件根本感知不到 → 所以按钮必须按 status 前置显示/禁用，不能靠兜底
 *
 * 状态机：loading → success / empty / error / forbidden（每个分支都可达，finally 必复位）
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { forceRefundOrder, getAdminOrderList, unfreezeOrder } from '@/api/admin'
import { formatDate, formatPrice } from '@/utils/format'
import { ADMIN_ORDER_STATUS_FILTERS, ADMIN_PAGE_SIZE, CODE, adminOrderStatusTabLabel, orderStatusLabel } from '@/utils/constants'

const router = useRouter()

const filterTabs = ADMIN_ORDER_STATUS_FILTERS
/** null = 全部 */
const statusFilter = ref(null)
const orderNo = ref('')
const page = ref(1)

const loading = ref(true)
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const forbidden = ref(false)

/**
 * 行级 + 操作级同步锁：`${id}:${action}`
 * 一行有 3 个操作（CANCEL / COMPLETE / forceRefund），只锁 id 的话
 * 「解冻中」的 loading 会串到「强制退款」按钮上，也拦不住同一行两个操作并发提交。
 */
const busyKey = ref('')

const isBusy = computed(() => busyKey.value !== '')
const isAllFilter = computed(() => statusFilter.value === null)
const filterLabel = computed(() =>
  isAllFilter.value ? '全部' : orderStatusLabel(statusFilter.value)
)
const hasOrderNo = computed(() => Boolean(orderNo.value.trim()))
const showPager = computed(() => total.value > ADMIN_PAGE_SIZE)
const isEmpty = computed(
  () => !loading.value && !errorMessage.value && !forbidden.value && records.value.length === 0
)

const keyOf = (row, action) => `${row?.id}:${action}`
const busyOn = (row, action) => busyKey.value === keyOf(row, action)

/** 可操作的两种状态（与后端一致：解冻只对 5，强制退款只对 6/7） */
const isFrozen = (row) => Number(row?.status) === 5
const isRefunding = (row) => [6, 7].includes(Number(row?.status))

// ------------------------------------------------------------------ 加载
async function fetchList() {
  loading.value = true
  errorMessage.value = ''
  forbidden.value = false
  try {
    const data = await getAdminOrderList(
      { status: statusFilter.value, orderNo: orderNo.value.trim(), page: page.value },
      { silent: true }
    )
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    console.warn('[admin-order] 订单列表加载失败：', error?.message)
    records.value = []
    total.value = 0
    if (error?.code === CODE.FORBIDDEN) {
      forbidden.value = true
      return
    }
    errorMessage.value = error?.message || '网络异常或服务不可用'
  } finally {
    loading.value = false
  }
}

function switchFilter(next) {
  if (next === statusFilter.value) return
  statusFilter.value = next
  page.value = 1 // 换筛选回到第 1 页
  fetchList()
}

function handleSearch() {
  page.value = 1
  fetchList()
}

function resetSearch() {
  orderNo.value = ''
  page.value = 1
  fetchList()
}

function handlePageChange(next) {
  page.value = next
  fetchList()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// ------------------------------------------------------------------ 操作
function handleActionError(error) {
  if (
    error?.code === CODE.STATUS_NOT_ALLOWED ||
    error?.code === CODE.PRODUCT_NOT_AVAILABLE ||
    error?.code === CODE.NO_PERMISSION
  ) {
    console.warn('[admin-order] 状态已变化，自动刷新列表：', error?.message)
    fetchList()
    ElMessage.info('列表已更新')
    return
  }
  if (error?.code === CODE.FORBIDDEN) {
    forbidden.value = true
    return
  }
  console.warn('[admin-order] 操作失败：', error?.message || error)
}

/**
 * 解冻处理
 * @param {'CANCEL'|'COMPLETE'} target 必须大写：后端是 @Pattern(regexp="CANCEL|COMPLETE")
 */
async function handleUnfreeze(row, target) {
  if (isBusy.value || !isFrozen(row)) return
  busyKey.value = keyOf(row, target)
  const toCancel = target === 'CANCEL'
  try {
    await ElMessageBox.confirm(
      toCancel
        ? '将把这笔冻结订单置为「已取消」，并**同步回补商品库存**（商品会重新可售）。订单不会自动恢复成原状态。'
        : '将把这笔冻结订单置为「已完成」，代表交易已在线下完成。**不回补库存**，也不会自动恢复成原状态。',
      toCancel ? `解冻为已取消：${row.orderNo}` : `线下处理完成：${row.orderNo}`,
      {
        confirmButtonText: toCancel ? '确认取消' : '确认完成',
        cancelButtonText: '返回',
        type: 'warning',
        customClass: 'cm-confirm-box'
      }
    )
    await unfreezeOrder(row.id, target)
    ElMessage.success(toCancel ? '已解冻并取消，库存已回补' : '已标记为线下完成')
    await fetchList()
  } catch (error) {
    handleActionError(error)
  } finally {
    busyKey.value = ''
  }
}

/** 管理员强制退款：6/7→4，后端会同步回补库存、写审计、通知买家 */
async function handleForceRefund(row) {
  if (isBusy.value || !isRefunding(row)) return
  busyKey.value = keyOf(row, 'forceRefund')
  try {
    const { value } = await ElMessageBox.prompt(
      '强制退款会把订单置为「已取消」并回补库存，原因会写入审计日志并通知买家，请写清楚依据。',
      `强制退款：${row.orderNo}`,
      {
        confirmButtonText: '确认退款',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：卖家超时未处理，管理员介入',
        // 后端 reason 可选，但审计日志里出现「原因=null」没有意义 → 这一层要求必填（纯 UI 约束）
        inputValidator: (value) => {
          const text = String(value ?? '').trim()
          if (!text) return '请填写退款原因'
          return text.length <= 200 || '原因不能超过 200 字'
        },
        customClass: 'cm-confirm-box'
      }
    )
    await forceRefundOrder(row.id, String(value).trim())
    ElMessage.success('已强制退款，库存已回补')
    await fetchList()
  } catch (error) {
    handleActionError(error)
  } finally {
    busyKey.value = ''
  }
}

function goHome() {
  router.push({ name: 'home' })
}

onMounted(fetchList)
</script>

<template>
  <section class="admin-order">
    <!-- ---------------- 工具栏 ---------------- -->
    <div class="admin-order__toolbar">
      <div class="admin-order__tabs" role="tablist">
        <button
          v-for="tab in filterTabs"
          :key="String(tab)"
          type="button"
          class="admin-order__tab"
          :class="{ 'is-active': statusFilter === tab }"
          role="tab"
          :aria-selected="statusFilter === tab"
          @click="switchFilter(tab)"
        >
          {{ adminOrderStatusTabLabel(tab) }}
        </button>
      </div>

      <div class="admin-order__search">
        <el-input
          v-model="orderNo"
          placeholder="订单号精确查询（需完整订单号）"
          clearable
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button v-if="hasOrderNo" plain :icon="Refresh" @click="resetSearch">重置</el-button>
      </div>
    </div>

    <!-- ---------------- ① loading ---------------- -->
    <div v-if="loading" class="admin-order__skeleton">
      <el-skeleton :rows="6" animated />
    </div>

    <!-- ---------------- ② 无权限（403） ---------------- -->
    <EmptyState
      v-else-if="forbidden"
      title="没有管理权限"
      description="当前账号不是管理员（后端返回 code=403）。请用管理员账号重新登录后再试。"
      action-text="返回首页"
      @action="goHome"
    />

    <!-- ---------------- ③ 加载失败 ---------------- -->
    <EmptyState
      v-else-if="errorMessage"
      title="加载失败，请重试"
      :description="errorMessage"
      action-text="重新加载"
      @action="fetchList"
    />

    <!-- ---------------- ④ 空列表 ---------------- -->
    <EmptyState
      v-else-if="isEmpty"
      :title="hasOrderNo ? '没有这个订单号' : isAllFilter ? '还没有任何订单' : `没有「${filterLabel}」的订单`"
      :description="
        hasOrderNo
          ? `订单号是精确匹配，请确认「${orderNo.trim()}」是否完整无误。`
          : '换个状态页签看看，或者稍后刷新。'
      "
      :action-text="hasOrderNo ? '清空查询条件' : ''"
      @action="resetSearch"
    />

    <!-- ---------------- ⑤ success ---------------- -->
    <template v-else>
      <p class="admin-order__count">
        共 <b class="cm-num">{{ total }}</b> 笔订单 · 按创建时间倒序
      </p>

      <el-table :data="records" row-key="id" class="admin-order__table">
        <el-table-column label="订单号" min-width="200">
          <template #default="{ row }">
            <span class="admin-order__no cm-num">{{ row.orderNo }}</span>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="200">
          <template #default="{ row }">
            <p class="admin-order__product">{{ row.productTitle || '（商品已删除）' }}</p>
          </template>
        </el-table-column>

        <el-table-column label="买家 / 卖家" width="150">
          <template #default="{ row }">
            <p class="admin-order__party">
              买家 <span class="cm-num">{{ row.userId }}</span>
            </p>
            <p class="admin-order__party">
              卖家 <span class="cm-num">{{ row.sellerId }}</span>
            </p>
          </template>
        </el-table-column>

        <el-table-column label="金额" width="110">
          <template #default="{ row }">
            <span class="admin-order__amount cm-num">¥{{ formatPrice(row.amount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="交易方式" width="110">
          <template #default="{ row }">
            <TradeTypeTag :type="row.tradeType" />
          </template>
        </el-table-column>

        <!-- 表头固定为中性，逐行由 TradeTypeTag 区分面交/邮寄；表头不随数据抖动 -->
        <el-table-column label="地点 / 地址" min-width="170">
          <template #default="{ row }">
            <span class="admin-order__place">{{ row.address || '未填写' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <OrderStatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            <span class="admin-order__time">{{ formatDate(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="240" align="right">
          <template #default="{ row }">
            <!-- 已冻结：两个终态选择（CANCEL 回补库存 / COMPLETE 不回补） -->
            <template v-if="isFrozen(row)">
              <el-button
                type="primary"
                size="small"
                :disabled="isBusy"
                :loading="busyOn(row, 'CANCEL')"
                @click="handleUnfreeze(row, 'CANCEL')"
              >
                解冻为已取消
              </el-button>
              <el-button
                plain
                size="small"
                :disabled="isBusy"
                :loading="busyOn(row, 'COMPLETE')"
                @click="handleUnfreeze(row, 'COMPLETE')"
              >
                线下完成
              </el-button>
            </template>

            <!-- 退款申请中 / 退款被拒：管理员强制退款 -->
            <el-button
              v-else-if="isRefunding(row)"
              plain
              type="danger"
              size="small"
              :disabled="isBusy"
              :loading="busyOn(row, 'forceRefund')"
              @click="handleForceRefund(row)"
            >
              强制退款
            </el-button>

            <!-- 其余状态没有任何管理动作：不放假按钮，直接留白 -->
            <span v-else class="admin-order__no-action">—</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="showPager" class="admin-order__pager">
        <el-pagination
          background
          layout="prev, pager, next, jumper, total"
          :total="total"
          :page-size="ADMIN_PAGE_SIZE"
          :current-page="page"
          @current-change="handlePageChange"
        />
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.admin-order {
  &__toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
    margin-bottom: 14px;
  }

  &__tabs {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 4px;
    padding: 4px;
    background: $cm-surface;
    border: 1px solid $cm-border;
    border-radius: $cm-radius-lg;
  }

  &__tab {
    height: 30px;
    padding: 0 14px;
    border: none;
    border-radius: $cm-radius-pill;
    background: transparent;
    font-size: 13px;
    font-weight: 600;
    color: $cm-text-secondary;
    cursor: pointer;
    transition:
      background 0.18s ease,
      color 0.18s ease;

    &:hover {
      color: $cm-text;
      background: $cm-hover-bg;
    }

    &.is-active {
      color: #fff;
      background: $cm-primary;
    }
  }

  &__search {
    display: flex;
    gap: 8px;
    align-items: center;

    :deep(.el-input) {
      width: 280px;
    }
  }

  &__skeleton {
    @include cm-card;
    padding: 20px;
  }

  &__count {
    font-size: 12.5px;
    color: $cm-text-secondary;
    margin-bottom: 10px;

    b {
      color: $cm-text;
    }
  }

  &__table {
    @include cm-card;
    overflow: hidden;

    :deep(.el-table__header th) {
      background: $cm-gray-tag-50;
      color: $cm-text;
      font-weight: 700;
    }

    :deep(.el-table__row:hover > td) {
      background: $cm-primary-50;
    }
  }

  &__no {
    font-size: 12.5px;
    font-weight: 600;
    color: $cm-text;
  }

  &__product {
    font-size: 13px;
    color: $cm-text;
    line-height: 1.45;
    @include cm-ellipsis-lines(2);
  }

  &__party {
    font-size: 12px;
    color: $cm-text-secondary;
    line-height: 1.6;
  }

  &__amount {
    font-weight: 700;
    color: $cm-accent;
  }

  &__time {
    font-size: 12.5px;
    color: $cm-text-secondary;
  }

  &__place {
    font-size: 12.5px;
    color: $cm-text-secondary;
    line-height: 1.5;
    @include cm-ellipsis-lines(2);
  }

  &__no-action {
    color: $cm-text-placeholder;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 18px;
  }
}
</style>
