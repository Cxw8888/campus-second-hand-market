<script setup>
/**
 * 管理端 · 用户管理（/admin/user）
 *
 * 数据源（已读源码核实）
 *   · 列表：GET /api/v1/admin/user/list（AdminController.java:69-73）
 *       - keyword 同时模糊匹配 **学号 / 昵称 / 邮箱** 三个字段（AdminServiceImpl.java:156-159），
 *         不是只匹配用户名 —— 所以输入框提示要写全，别让管理员以为只能搜学号
 *       - status 字段**没有默认值** → 省略即「全部」（AdminUserQuery.java:21-22），
 *         这一点和商品列表（status 默认 3）完全相反，所以这里可以有「全部」页签
 *       - 按 createTime **降序**
 *   · 封禁：**PUT** /api/v1/admin/user/ban/{id}（AdminController.java:77）
 *   · 解封：**PUT** /api/v1/admin/user/unban/{id}（AdminController.java:84）
 *     ⚠️ 需求文档里写的是 POST —— 后端实际是 PUT，照 POST 写会 405。以源码为准。
 *
 * ⚠️ 后端 banUser **没有「不能封禁自己」的校验**：通读 AdminServiceImpl.java:164-216，
 *    只判了「用户是否存在」和「是否重复封禁」，**全程没有读 UserContext、也没有任何角色判断**
 *    （已用 grep 核实该文件里不存在 UserContext / 当前登录用户 id 的引用）。
 *    也就是说管理员把自己封了，会连锁发生：自己名下在售商品被下架 → 自己未完成订单被冻结并回补库存
 *    → token version+1 → **下一个请求就 401 被踢出登录**，而且没有任何入口能自己解封。
 *    所以这条边界只能由前端守住（禁用 + tooltip），后端没有兜底。
 *    同理：后端也不禁止封禁**其他管理员**，本批未做限制（不在需求范围内），只在报告里提示。
 *
 * 状态机：loading → success / empty / error / forbidden（每个分支都可达，finally 必复位）
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import { banUser, getAdminProductList, getAdminUserList, unbanUser } from '@/api/admin'
import { formatDate } from '@/utils/format'
import {
  ADMIN_PAGE_SIZE,
  ADMIN_ROLE_FILTERS,
  ADMIN_USER_STATUS_FILTERS,
  CODE,
  MAX_PAGE_SIZE,
  adminRoleLabel,
  adminRoleTone,
  adminUserStatusLabel,
  adminUserStatusTone,
  tagTypeOf
} from '@/utils/constants'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const filterTabs = ADMIN_USER_STATUS_FILTERS
const roleTabs = ADMIN_ROLE_FILTERS
/** null = 全部（后端 status 无默认值，省略即不过滤） */
const statusFilter = ref(null)
/**
 * 角色筛选：null = 全部
 *
 * ⚠️ 后端 `AdminUserQuery` **没有 role 字段**（只有 keyword + status，已读源码核实），
 *    所以这个筛选没有服务端支持 —— 选中角色后会：
 *      ① 把当前 status/keyword 条件下的用户**一次拉满**（size = 100，后端 @Max 上限）
 *      ② 在前端按 role 过滤 + 前端分页
 *    若库里用户总数超过 100，就只覆盖前 100 条，界面会**明确提示**（truncated），不假装完整。
 *    后端补上 role 参数后，这里可以退回纯服务端分页。
 */
const roleFilter = ref(null)
const keyword = ref('')
const page = ref(1)

const loading = ref(true)
const records = ref([])
const total = ref(0)
/** 角色筛选模式下的一次性全量数据（≤ MAX_PAGE_SIZE 条）与其后端总数 */
const poolRecords = ref([])
const poolTotal = ref(0)
const errorMessage = ref('')
/** 403 单独一态：不是"加载失败"，提示语与出口都不同 */
const forbidden = ref(false)

/**
 * 行级 + 操作级同步锁
 *
 * 5.1 用的是 busyId（一行一个操作），但订单页一行有 3 个操作（解冻取消/线下完成/强制退款），
 * 只锁 id 锁不住"到底是哪个操作在提交"，按钮 loading 也会串行显示。
 * 所以统一用 `${id}:${action}`。
 */
const busyKey = ref('')

const currentUserId = computed(() => String(userStore.userId || ''))
const isBusy = computed(() => busyKey.value !== '')

/** 角色筛选模式：数据在本地过滤 + 本地分页 */
const clientMode = computed(() => roleFilter.value !== null)
const roleFiltered = computed(() =>
  clientMode.value ? poolRecords.value.filter((row) => Number(row?.role) === roleFilter.value) : []
)
/** 表格真正渲染的数据：服务端分页 vs 本地过滤后切片 */
const displayRecords = computed(() => {
  if (!clientMode.value) return records.value
  const start = (page.value - 1) * ADMIN_PAGE_SIZE
  return roleFiltered.value.slice(start, start + ADMIN_PAGE_SIZE)
})
const displayTotal = computed(() => (clientMode.value ? roleFiltered.value.length : total.value))
/** 库里用户数超过一次性能拉取的上限 → 角色筛选结果可能不完整，必须如实提示 */
const truncated = computed(() => clientMode.value && poolTotal.value > poolRecords.value.length)

const isAllFilter = computed(() => statusFilter.value === null)
const filterLabel = computed(() =>
  isAllFilter.value ? '全部' : adminUserStatusLabel(statusFilter.value)
)
const roleFilterLabel = computed(() =>
  roleFilter.value === null ? '全部' : adminRoleLabel(roleFilter.value)
)
const hasKeyword = computed(() => Boolean(keyword.value.trim()))
const showPager = computed(() => displayTotal.value > ADMIN_PAGE_SIZE)
const isEmpty = computed(
  () =>
    !loading.value &&
    !errorMessage.value &&
    !forbidden.value &&
    displayRecords.value.length === 0
)

const keyOf = (row, action) => `${row?.id}:${action}`
const busyOn = (row, action) => busyKey.value === keyOf(row, action)
const isBanned = (row) => Number(row?.status) === 1
/** 自比较：用 store 的 userId（由 LoginVO.userId 写入、兜底时由 UserVO.id 映射而来） */
const isSelf = (row) => currentUserId.value !== '' && String(row?.id) === currentUserId.value
/** 已封禁 / 是自己 → 封禁按钮禁用（幂等靠前置禁用，不靠错误码兜底） */
const isBanDisabled = (row) => isSelf(row) || isBanned(row)
const banTip = (row) =>
  isSelf(row) ? '不能封禁当前登录账号' : '该用户已处于封禁状态，无需重复操作'

// ------------------------------------------------------------------ 加载
async function fetchList() {
  loading.value = true
  errorMessage.value = ''
  forbidden.value = false
  try {
    // 角色筛选模式：一次性拉满（page=1 + size=100），交给前端过滤/分页
    const params = clientMode.value
      ? {
          status: statusFilter.value,
          keyword: keyword.value.trim(),
          page: 1,
          size: MAX_PAGE_SIZE
        }
      : { status: statusFilter.value, keyword: keyword.value.trim(), page: page.value }

    const data = await getAdminUserList(params, { silent: true })
    const list = Array.isArray(data?.records) ? data.records : []

    if (clientMode.value) {
      poolRecords.value = list
      poolTotal.value = Number(data?.total ?? 0)
      records.value = []
      total.value = 0
    } else {
      records.value = list
      total.value = Number(data?.total ?? 0)
      poolRecords.value = []
      poolTotal.value = 0
    }
  } catch (error) {
    console.warn('[admin-user] 用户列表加载失败：', error?.message)
    records.value = []
    total.value = 0
    poolRecords.value = []
    poolTotal.value = 0
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
  page.value = 1 // 换筛选必须回到第 1 页，否则会停在越界页码上看到空列表
  fetchList()
}

/** 切换角色筛选（同样的规则：回第 1 页） */
function switchRole(next) {
  if (next === roleFilter.value) return
  roleFilter.value = next
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
 * 统一错误处理
 *
 * · 209 / 204 / 203：说明列表数据已经过期（别处刚改过）→ 静默刷新 + info，不弹"操作失败"
 * · 403：角色被中途收回 → 切到无权限态
 * · 其余：拦截器已经弹过提示（本页列表用 silent，但写操作不是），这里只留控制台
 */
function handleActionError(error) {
  if (
    error?.code === CODE.STATUS_NOT_ALLOWED ||
    error?.code === CODE.PRODUCT_NOT_AVAILABLE ||
    error?.code === CODE.NO_PERMISSION
  ) {
    console.warn('[admin-user] 状态已变化，自动刷新列表：', error?.message)
    fetchList()
    ElMessage.info('列表已更新')
    return
  }
  if (error?.code === CODE.FORBIDDEN) {
    forbidden.value = true
    return
  }
  console.warn('[admin-user] 操作失败：', error?.message || error)
}

async function handleBan(row) {
  if (isBusy.value || isBanDisabled(row)) return
  busyKey.value = keyOf(row, 'ban') // 同步上锁：必须在第一个 await 之前
  try {
    await ElMessageBox.confirm(
      '封禁会同时做三件事：该账号被强制下线、名下**在售商品全部下架**、未完成订单被冻结并回补库存。此操作不可逆（如需恢复要再手动解封）。',
      `确认封禁「${row.nickname || row.username}」？`,
      {
        confirmButtonText: '确认封禁',
        cancelButtonText: '取消',
        type: 'warning',
        customClass: 'cm-confirm-box'
      }
    )
    await banUser(row.id)

    // 封禁接口返回的是 Result<Void>（data 为 null），前端**拿不到**"下架了几件"，
    // 所以补一次静默查询统计该用户名下处于「已下架」状态的商品数。
    // 注意口径：这是"名下当前处于下架状态的商品总数"（含封禁前就已下架的），不是"本次封禁新下架的数量"。
    let offShelf = null
    try {
      const data = await getAdminProductList({ userId: row.id, status: 0 }, { silent: true })
      offShelf = Number(data?.total ?? 0)
    } catch (error) {
      console.warn('[admin-user] 统计该用户下架商品失败：', error?.message)
    }

    ElMessage.success(
      offShelf === null
        ? '已封禁该用户，其在售商品已下架、未完成订单已冻结'
        : `已封禁，该用户名下 ${offShelf} 件商品已下架`
    )
    await fetchList()
  } catch (error) {
    handleActionError(error)
  } finally {
    busyKey.value = ''
  }
}

async function handleUnban(row) {
  if (isBusy.value || !isBanned(row)) return
  busyKey.value = keyOf(row, 'unban')
  try {
    await ElMessageBox.confirm(
      '解封后该用户可以重新登录。注意：名下已被冻结的订单仍保持「待线下处理」，不会自动恢复成原状态。',
      `确认解封「${row.nickname || row.username}」？`,
      {
        confirmButtonText: '确认解封',
        cancelButtonText: '取消',
        type: 'warning',
        customClass: 'cm-confirm-box'
      }
    )
    await unbanUser(row.id)
    ElMessage.success('已解封该用户')
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
  <section class="admin-user">
    <!-- ---------------- 工具栏 ---------------- -->
    <div class="admin-user__toolbar">
      <div class="admin-user__filters">
        <div class="admin-user__tabs" role="tablist" aria-label="状态筛选">
          <button
            v-for="tab in filterTabs"
            :key="`status-${String(tab)}`"
            type="button"
            class="admin-user__tab"
            :class="{ 'is-active': statusFilter === tab }"
            role="tab"
            :aria-selected="statusFilter === tab"
            @click="switchFilter(tab)"
          >
            {{ tab === null ? '全部' : adminUserStatusLabel(tab) }}
          </button>
        </div>

        <!-- 角色筛选：后端 AdminUserQuery 没有 role 参数 → 前端在拉取到的数据里过滤（见 script 注释） -->
        <div class="admin-user__tabs" role="tablist" aria-label="角色筛选">
          <span class="admin-user__tabs-label">角色</span>
          <button
            v-for="tab in roleTabs"
            :key="`role-${String(tab)}`"
            type="button"
            class="admin-user__tab"
            :class="{ 'is-active': roleFilter === tab }"
            role="tab"
            :aria-selected="roleFilter === tab"
            @click="switchRole(tab)"
          >
            {{ tab === null ? '全部' : adminRoleLabel(tab) }}
          </button>
        </div>
      </div>

      <div class="admin-user__search">
        <el-input
          v-model="keyword"
          placeholder="搜索学号 / 昵称 / 邮箱"
          clearable
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-if="hasKeyword" plain :icon="Refresh" @click="resetSearch">重置</el-button>
      </div>
    </div>

    <!-- 角色筛选只覆盖一次性能拉取的上限：如实提示，不假装完整。
         放在状态分支**之外**，这样即使筛选结果为空（走空态）也能看到这条说明。 -->
    <el-alert
      v-if="truncated"
      class="admin-user__alert"
      type="warning"
      show-icon
      :closable="false"
      title="角色筛选结果可能不完整"
      :description="`后端 AdminUserQuery 不支持按角色查询，本页只能在前 ${MAX_PAGE_SIZE} 条里过滤；当前库里用户总数为 ${poolTotal}，建议先用搜索缩小范围再筛选。`"
    />

    <!-- ---------------- ① loading ---------------- -->
    <div v-if="loading" class="admin-user__skeleton">
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
      :title="
        hasKeyword
          ? '没有匹配的用户'
          : clientMode
            ? `没有「${roleFilterLabel}」角色的用户`
            : isAllFilter
              ? '还没有任何注册用户'
              : `没有「${filterLabel}」状态的用户`
      "
      :description="
        hasKeyword
          ? `没有学号 / 昵称 / 邮箱包含「${keyword.trim()}」的${filterLabel}用户。`
          : '换个筛选条件看看，或者稍后刷新。'
      "
      :action-text="hasKeyword ? '清空搜索条件' : ''"
      @action="resetSearch"
    />

    <!-- ---------------- ⑤ success ---------------- -->
    <template v-else>
      <p class="admin-user__count">
        共 <b class="cm-num">{{ displayTotal }}</b> 位用户 · 按注册时间倒序
        <span v-if="clientMode" class="admin-user__hint">
          （角色筛选＝「{{ roleFilterLabel }}」，由前端在已拉取的数据中过滤）
        </span>
      </p>

      <!-- 角色筛选只覆盖一次性能拉取的上限：已在页面顶部统一提示（truncated），这里不重复 -->

      <el-table :data="displayRecords" row-key="id" class="admin-user__table">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="admin-user__cell">
              <span class="admin-user__avatar">{{ (row.nickname || row.username || '？').trim().charAt(0) }}</span>
              <div class="admin-user__info">
                <p class="admin-user__name">{{ row.nickname || '未命名用户' }}</p>
                <p class="admin-user__meta">
                  ID <span class="cm-num">{{ row.id }}</span>
                </p>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="学号" width="130">
          <template #default="{ row }">
            <span class="admin-user__username cm-num">{{ row.username }}</span>
          </template>
        </el-table-column>

        <el-table-column label="邮箱" min-width="200">
          <template #default="{ row }">
            <span class="admin-user__email">{{ row.email || '未填写' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="tagTypeOf(adminRoleTone(row.role))" effect="light" round>
              {{ adminRoleLabel(row.role) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="tagTypeOf(adminUserStatusTone(row.status))" effect="light" round>
              {{ adminUserStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="注册时间" width="160">
          <template #default="{ row }">
            <span class="admin-user__time">{{ formatDate(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" align="right">
          <template #default="{ row }">
            <el-tooltip :content="banTip(row)" :disabled="!isBanDisabled(row)" placement="top">
              <span class="admin-user__btn-wrap">
                <el-button
                  plain
                  type="danger"
                  size="small"
                  :disabled="isBanDisabled(row) || isBusy"
                  :loading="busyOn(row, 'ban')"
                  @click="handleBan(row)"
                >
                  封禁
                </el-button>
              </span>
            </el-tooltip>

            <!-- 解封只在已封禁时出现（未封禁的用户没有可解的东西） -->
            <el-button
              v-if="isBanned(row)"
              type="primary"
              plain
              size="small"
              :disabled="isBusy"
              :loading="busyOn(row, 'unban')"
              @click="handleUnban(row)"
            >
              解封
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="showPager" class="admin-user__pager">
        <el-pagination
          background
          layout="prev, pager, next, jumper, total"
          :total="displayTotal"
          :page-size="ADMIN_PAGE_SIZE"
          :current-page="page"
          @current-change="handlePageChange"
        />
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.admin-user {
  &__toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
    margin-bottom: 14px;
  }

  &__filters {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  &__tabs {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px;
    background: $cm-surface;
    border: 1px solid $cm-border;
    border-radius: $cm-radius-pill;
  }

  &__tabs-label {
    font-size: 12px;
    font-weight: 700;
    color: $cm-text-placeholder;
    padding: 0 6px 0 8px;
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
      width: 260px;
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

  &__hint {
    color: $cm-text-placeholder;
  }

  &__alert {
    margin-bottom: 10px;
  }

  &__username {
    font-size: 13px;
    color: $cm-text;
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

  &__cell {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }

  &__avatar {
    width: 34px;
    height: 34px;
    flex: 0 0 34px;
    border-radius: 50%;
    background: $cm-gradient-brand;
    color: #fff;
    font-size: 14px;
    font-weight: 700;
    @include cm-center;
  }

  &__info {
    min-width: 0;
  }

  &__name {
    font-size: 13.5px;
    font-weight: 600;
    color: $cm-text;
    @include cm-ellipsis;
  }

  &__meta {
    font-size: 11.5px;
    color: $cm-text-placeholder;
    margin-top: 2px;
    @include cm-ellipsis;
  }

  &__email {
    font-size: 12.5px;
    color: $cm-text-secondary;
    @include cm-ellipsis;
  }

  &__time {
    font-size: 12.5px;
    color: $cm-text-secondary;
  }

  &__btn-wrap {
    display: inline-flex;
    margin-right: 8px;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 18px;
  }
}
</style>
