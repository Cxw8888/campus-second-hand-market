<script setup>
/**
 * 管理端 · 商品审核（/admin/product/audit）
 *
 * 数据源：GET /api/v1/admin/product/audit/list（AdminController.java:47-50）
 *   · 后端 **createTime 升序** —— 审核队列本来就是"最早的先审"，不要在前端再排一次
 *   · status 必须显式传：字段默认值是 3，省略参数 = 只查待审核（AdminProductQuery.java:19）
 *
 * 四个状态页签固定为 ADMIN_PRODUCT_STATUS_TABS，**没有「全部」**（原因见 constants.js 注释）。
 *
 * 状态机：loading → success / empty / error / forbidden
 *   （每个分支都必然可达，且 finally 一定会把 loading 复位 —— 批次 3 的骨架屏事故就是这么来的）
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ProductImage from '@/components/ProductImage.vue'
import ProductStatusTag from '@/components/ProductStatusTag.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { auditProduct, forceOfflineProduct, getAdminProductList } from '@/api/admin'
import { formatDate, formatPrice } from '@/utils/format'
import { ADMIN_PAGE_SIZE, ADMIN_PRODUCT_STATUS_TABS, CODE, productStatusLabel } from '@/utils/constants'

const router = useRouter()

/** 页签取的是「后端能精确过滤」的 4 个状态值，文案统一走 productStatusLabel */
const tabs = ADMIN_PRODUCT_STATUS_TABS

const status = ref(ADMIN_PRODUCT_STATUS_TABS[0]) // 默认 3-待审核
const keyword = ref('')
const page = ref(1)

const loading = ref(true)
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
/** 403 单独一态：不是"加载失败"，而是这个账号没有管理权限，提示语和出口都不一样 */
const forbidden = ref(false)

/** 行级同步锁：必须在任何 await 之前赋值，否则快速双击会出两个确认弹窗（批次 4 踩过） */
const busyId = ref('')

const isPendingTab = computed(() => status.value === 3)
const hasKeyword = computed(() => Boolean(keyword.value.trim()))
const showPager = computed(() => total.value > ADMIN_PAGE_SIZE)
const isEmpty = computed(() => !loading.value && !errorMessage.value && !forbidden.value && records.value.length === 0)
const isBusy = computed(() => busyId.value !== '')

// ------------------------------------------------------------------ 加载
async function fetchList() {
  loading.value = true
  errorMessage.value = ''
  forbidden.value = false
  try {
    // silent：页面自己渲染错误态，不需要拦截器再弹一个 toast
    const data = await getAdminProductList(
      { status: status.value, keyword: keyword.value.trim(), page: page.value },
      { silent: true }
    )
    // total / pages 被后端 Long→String 序列化，比较前必须 Number()
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    console.warn('[admin-product] 审核列表加载失败：', error?.message)
    records.value = []
    total.value = 0
    if (error?.code === CODE.FORBIDDEN) {
      forbidden.value = true
      // 权限可能是中途被收回的（比如管理员账号被降权），退回前台比留在空壳页面更清楚
      return
    }
    errorMessage.value = error?.message || '网络异常或服务不可用'
  } finally {
    loading.value = false
  }
}

function switchTab(next) {
  if (next === status.value) return
  status.value = next
  page.value = 1
  fetchList()
}

function handleSearch() {
  page.value = 1
  fetchList()
}

function resetSearch() {
  keyword.value = ''
  page.value = 1
  fetchList()
}

function handlePageChange(next) {
  page.value = next
  fetchList()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// ------------------------------------------------------------------ 操作
/**
 * 统一处理操作失败
 *
 * ⚠️ 后端两类"失败"长得完全不一样，必须分开对待：
 *   · code=209 / 204：说明**列表数据过期了**（别的管理员刚处理过）→ 静默刷新让页面回到真实状态
 *     （拦截器已经弹过"当前状态不允许此操作"，这里不再重复弹）
 *   · code=200 + msg="请勿重复操作"：那是**成功码**，前端拦截器直接 resolve，组件根本感知不到
 *     （AdminServiceImpl.java:171-173 / 288-290 抛的是 ErrorCode.SUCCESS）→
 *     所以按钮必须按列表里的 status 前置禁用，不能指望这里兜住
 */
function handleActionError(error) {
  if (error?.code === CODE.STATUS_NOT_ALLOWED || error?.code === CODE.PRODUCT_NOT_AVAILABLE) {
    console.warn('[admin-product] 状态已变化，自动刷新列表：', error?.message)
    fetchList()
    return
  }
  if (error?.code === CODE.FORBIDDEN) {
    forbidden.value = true
    return
  }
  // 其余情况（含用户点了取消）：拦截器已提示过，或本来就不需要提示
  console.warn('[admin-product] 操作失败：', error?.message || error)
}

/** 审核通过 / 驳回；pass=false 时要求填写驳回原因（会随站内信发给卖家） */
async function handleAudit(row, pass) {
  if (isBusy.value) return
  busyId.value = String(row.id) // 同步上锁，务必在第一个 await 之前
  try {
    let reason = ''
    if (!pass) {
      const { value } = await ElMessageBox.prompt(
        `驳回原因会随站内信一起发给商品发布者，请写清楚问题所在。`,
        `驳回「${row.title}」`,
        {
          confirmButtonText: '确认驳回',
          cancelButtonText: '取消',
          inputPlaceholder: '例如：图片不清晰 / 描述与实物不符',
          // 前端与后端一致：≤200 字（ProductAuditRequest.java:23 @Size(max=200)）
          inputValidator: (value) => {
            const text = String(value ?? '').trim()
            if (!text) return '请填写驳回原因'
            return text.length <= 200 || '原因不能超过 200 字'
          },
          customClass: 'cm-confirm-box'
        }
      )
      reason = String(value).trim()
    }

    await auditProduct(row.id, { pass, reason })
    ElMessage.success(pass ? '已通过审核，商品已上架' : '已驳回，并已通知卖家')
    await fetchList()
  } catch (error) {
    handleActionError(error)
  } finally {
    busyId.value = ''
  }
}

/** 强制下架（任意状态 → 0；后端不校验当前状态，所以要对「已下架」前置禁用） */
async function handleForceOffline(row) {
  if (isBusy.value) return
  busyId.value = String(row.id)
  try {
    const { value } = await ElMessageBox.prompt(
      '强制下架是不可逆的管理动作，原因会写进审计日志并通知发布者。',
      `强制下架「${row.title}」`,
      {
        confirmButtonText: '确认下架',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：涉嫌违规内容',
        // 后端 reason 是可选的，但审计日志里出现「原因=null」没有意义，
        // 所以这一层要求必填（纯 UI 约束，不影响接口契约）
        inputValidator: (value) => {
          const text = String(value ?? '').trim()
          if (!text) return '请填写下架原因'
          return text.length <= 200 || '原因不能超过 200 字'
        },
        customClass: 'cm-confirm-box'
      }
    )
    await forceOfflineProduct(row.id, String(value).trim())
    ElMessage.success('已强制下架')
    await fetchList()
  } catch (error) {
    handleActionError(error)
  } finally {
    busyId.value = ''
  }
}

function goHome() {
  router.push({ name: 'home' })
}

onMounted(fetchList)
</script>

<template>
  <section class="admin-product">
    <!-- ---------------- 工具栏 ---------------- -->
    <div class="admin-product__toolbar">
      <div class="admin-product__tabs" role="tablist">
        <button
          v-for="tab in tabs"
          :key="tab"
          type="button"
          class="admin-product__tab"
          :class="{ 'is-active': status === tab }"
          role="tab"
          :aria-selected="status === tab"
          @click="switchTab(tab)"
        >
          {{ productStatusLabel(tab) }}
        </button>
      </div>

      <div class="admin-product__search">
        <el-input
          v-model="keyword"
          placeholder="按商品标题搜索"
          clearable
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-if="hasKeyword" plain :icon="Refresh" @click="resetSearch">重置</el-button>
      </div>
    </div>

    <!-- ---------------- ① loading ---------------- -->
    <div v-if="loading" class="admin-product__skeleton">
      <el-skeleton :rows="6" animated />
    </div>

    <!-- ---------------- ② 无权限（403） ---------------- -->
    <EmptyState
      v-else-if="forbidden"
      title="没有管理权限"
      description="当前账号不是管理员（后端返回 code=403）。如果你确实需要审核商品，请用管理员账号重新登录。"
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
      :title="hasKeyword ? '没有匹配的商品' : `没有${productStatusLabel(status)}的商品`"
      :description="
        hasKeyword
          ? `当前「${productStatusLabel(status)}」下没有标题包含「${keyword.trim()}」的商品。`
          : '换个状态页签看看，或者稍后刷新。'
      "
      :action-text="hasKeyword ? '清空搜索条件' : ''"
      @action="resetSearch"
    />

    <!-- ---------------- ⑤ success ---------------- -->
    <template v-else>
      <p class="admin-product__count">
        共 <b class="cm-num">{{ total }}</b> 件 ·
        <span class="admin-product__order-tip">按发布时间正序（最早的先审）</span>
      </p>

      <el-table :data="records" row-key="id" class="admin-product__table">
        <el-table-column label="商品" min-width="300">
          <template #default="{ row }">
            <div class="admin-product__cell">
              <div class="admin-product__cover">
                <ProductImage :src="row.coverImage" :alt="row.title" ratio="1 / 1" :icon-size="20" />
              </div>
              <div class="admin-product__info">
                <p class="admin-product__name">{{ row.title }}</p>
                <p class="admin-product__meta">
                  {{ row.categoryName || '分类未知' }} · 库存 {{ row.stock }} ·
                  编号 <span class="cm-num">{{ row.id }}</span>
                </p>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="卖家" width="130">
          <template #default="{ row }">
            <span class="admin-product__seller">{{ row.sellerNickname || '未命名用户' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="价格" width="110">
          <template #default="{ row }">
            <span class="admin-product__price cm-num">¥{{ formatPrice(row.price) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="交易方式" width="110">
          <template #default="{ row }">
            <TradeTypeTag :type="row.tradeType" />
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <ProductStatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">
            <span class="admin-product__time">{{ formatDate(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="190" align="right">
          <template #default="{ row }">
            <!-- 待审核：通过 / 驳回 -->
            <template v-if="Number(row.status) === 3">
              <el-button
                type="primary"
                size="small"
                :disabled="isBusy"
                :loading="busyId === String(row.id)"
                @click="handleAudit(row, true)"
              >
                通过
              </el-button>
              <el-button
                plain
                type="danger"
                size="small"
                :disabled="isBusy"
                @click="handleAudit(row, false)"
              >
                驳回
              </el-button>
            </template>

            <!-- 非待审核：强制下架；已是下架状态则禁用并说明原因 -->
            <el-tooltip
              v-else
              content="该商品已是下架状态，无需重复操作"
              :disabled="Number(row.status) !== 0"
            >
              <span class="admin-product__btn-wrap">
                <el-button
                  plain
                  type="danger"
                  size="small"
                  :disabled="Number(row.status) === 0 || isBusy"
                  :loading="busyId === String(row.id)"
                  @click="handleForceOffline(row)"
                >
                  强制下架
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="showPager" class="admin-product__pager">
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
.admin-product {
  // ---------------- 工具栏 ----------------
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
    gap: 4px;
    padding: 4px;
    background: $cm-surface;
    border: 1px solid $cm-border;
    border-radius: $cm-radius-pill;
  }

  &__tab {
    height: 30px;
    padding: 0 16px;
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
      width: 240px;
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

  &__order-tip {
    color: $cm-text-placeholder;
  }

  // ---------------- 表格 ----------------
  &__table {
    @include cm-card;
    // 表格自己带边框，这里只补外阴影与圆角，避免和卡片混在一起显得脏
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

  &__cell {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }

  &__cover {
    width: 48px;
    flex: 0 0 48px;
    border-radius: $cm-radius-sm;
    overflow: hidden;
  }

  &__info {
    min-width: 0;
  }

  &__name {
    font-size: 13.5px;
    font-weight: 600;
    color: $cm-text;
    line-height: 1.45;
    @include cm-ellipsis-lines(2);
  }

  &__meta {
    font-size: 11.5px;
    color: $cm-text-placeholder;
    margin-top: 2px;
    @include cm-ellipsis;
  }

  &__seller {
    font-size: 13px;
    color: $cm-text-secondary;
  }

  &__price {
    font-weight: 700;
    color: $cm-accent;
  }

  &__time {
    font-size: 12.5px;
    color: $cm-text-secondary;
  }

  &__btn-wrap {
    display: inline-flex;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 18px;
  }
}
</style>
