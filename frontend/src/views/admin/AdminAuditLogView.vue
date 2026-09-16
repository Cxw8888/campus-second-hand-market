<script setup>
/**
 * 管理端 · 审计日志（/admin/audit-log）—— **只读页：没有任何操作按钮**
 *
 * ===================== 后端契约（已读源码核实） =====================
 * · 列表：GET /api/v1/admin/audit-log/list（AdminController.java:116-119）
 *     query：operatorId? / operationType? / startTime? / endTime? / page / size
 *     → PageResult<AuditLogVO>，按 createTime **降序**
 * · AuditLogVO：id / operatorId / operatorName / operationType / targetType / targetId /
 *     result(1-成功 0-失败) / detail / ip / createTime
 * · AuditLogQuery：startTime / endTime 是 LocalDateTime 且
 *     `@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")`（AuditLogQuery.java:28-36）
 *     → 前端**必须**传 "YYYY-MM-DD HH:mm:ss" 字符串，绝不能传时间戳
 * · operationType 是 **11 个字符串枚举**（AuditOperationType.java:8-42），库里按字符串**精确匹配**
 *
 * ===================== 三个已核实的"数据本身的问题"（如实反映，不美化） =====================
 * ① `operatorName` 存在**但不是昵称**：后端写入的是用户 ID 的字符串 ——
 *    `auditLog.setOperatorName(loginUser == null ? "SYSTEM" : String.valueOf(loginUser.getUserId()))`
 *    （AdminAuditService.java:62）。所以本页按需求显示 `operatorName || operatorId`
 *    （两者实际是同一个值），并在悬浮提示里点明它是 ID、不是昵称 → 报告「后端补丁建议」。
 * ② 目标列**不做跳转**：targetType 是 PRODUCT / USER / ORDER / CATEGORY（AdminAuditService.java:43），
 *    但管理端三个页面都**不支持按 id 查询** —— AdminUserQuery 只有 keyword/status（keyword 匹配
 *    学号/昵称/邮箱，不含 id）；AdminOrderQuery 的 orderNo 是订单号不是订单 id；
 *    AdminProductQuery 只有 status/keyword/userId（keyword 匹配标题）。
 *    跳过去只会落在错误的筛选结果上，所以按需求给的兜底：只显示「类型:id」，便于复制到别处查。
 * ③ 隐私：AuditLogVO **没有手机号/邮箱字段**，detail 是服务端拼的固定文案
 *    （如「审核结果=通过, 原因=…」「分类迁移商品到 2, 迁移条数=3」），不含 PII → 可安全展示。
 *    `ip` 字段本页**不展示**（属敏感信息，且不在本页需求内，最小化暴露）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import { getAuditLogList } from '@/api/admin'
import { formatDate } from '@/utils/format'
import {
  ADMIN_PAGE_SIZE,
  AUDIT_OPERATION_TYPES,
  CODE,
  auditOperationLabel,
  auditOperationTone,
  auditResultLabel,
  auditResultTone,
  auditTargetTypeLabel,
  tagTypeOf
} from '@/utils/constants'

const router = useRouter()

const operationType = ref('')
/** el-date-editor 的 datetimerange 值：['YYYY-MM-DD HH:mm:ss','YYYY-MM-DD HH:mm:ss']；**默认留空 = 全部** */
const timeRange = ref([])
const page = ref(1)

const loading = ref(true)
const records = ref([])
const total = ref(0)
const errorMessage = ref('')
const forbidden = ref(false)

const isBusy = computed(() => loading.value)
const hasFilter = computed(() => Boolean(operationType.value) || (timeRange.value?.length ?? 0) > 0)
const showPager = computed(() => total.value > ADMIN_PAGE_SIZE)
const isEmpty = computed(
  () => !loading.value && !errorMessage.value && !forbidden.value && records.value.length === 0
)

/** 快捷时间范围（值仍是 Date，由 value-format 转成字符串，和手选走同一套格式） */
const dateShortcuts = [
  {
    text: '今天',
    value: () => {
      const start = new Date()
      start.setHours(0, 0, 0, 0)
      return [start, new Date()]
    }
  },
  {
    text: '近 7 天',
    value: () => {
      const start = new Date()
      start.setDate(start.getDate() - 6)
      start.setHours(0, 0, 0, 0)
      return [start, new Date()]
    }
  },
  {
    text: '近 30 天',
    value: () => {
      const start = new Date()
      start.setDate(start.getDate() - 29)
      start.setHours(0, 0, 0, 0)
      return [start, new Date()]
    }
  }
]

/** 时间筛选：只有两段都选齐了才下发，避免出现"只有开始没有结束"的半残条件 */
const startTime = computed(() => (timeRange.value?.[0] ? timeRange.value[0] : undefined))
const endTime = computed(() => (timeRange.value?.[1] ? timeRange.value[1] : undefined))

// ------------------------------------------------------------------ 加载
async function fetchList() {
  loading.value = true
  errorMessage.value = ''
  forbidden.value = false
  try {
    const data = await getAuditLogList(
      {
        operationType: operationType.value || undefined,
        startTime: startTime.value,
        endTime: endTime.value,
        page: page.value
      },
      { silent: true }
    )
    total.value = Number(data?.total ?? 0)
    records.value = Array.isArray(data?.records) ? data.records : []
  } catch (error) {
    console.warn('[admin-audit-log] 审计日志加载失败：', error?.message)
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

function applyFilter() {
  page.value = 1
  fetchList()
}

function resetFilter() {
  operationType.value = ''
  timeRange.value = []
  page.value = 1
  fetchList()
}

function handlePageChange(next) {
  page.value = next
  fetchList()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 操作人：优先 operatorName（后端实际写入的是用户 ID 字符串），没有才用 operatorId */
const operatorOf = (row) => row?.operatorName || row?.operatorId || '—'

function goHome() {
  router.push({ name: 'home' })
}

onMounted(fetchList)
</script>

<template>
  <section class="admin-audit">
    <!-- ---------------- 筛选栏（只读页：只有筛选，没有任何写操作） ---------------- -->
    <div class="admin-audit__filters">
      <el-select
        v-model="operationType"
        placeholder="全部操作类型"
        clearable
        class="admin-audit__select"
        @change="applyFilter"
        @clear="applyFilter"
      >
        <el-option
          v-for="type in AUDIT_OPERATION_TYPES"
          :key="type"
          :label="auditOperationLabel(type)"
          :value="type"
        />
      </el-select>

      <!-- ⚠️ 用 el-date-picker（不是旧标签名 el-date-editor）：
           EP 2.14.5 里没有 date-editor 这个组件，自动导入解析器会去找
           `element-plus/es/components/date-editor/style/css` 而直接构建失败（5.3 实测踩到）。
           type="datetimerange" 就是需求要的「日期时间范围」选择器，渲染与行为一致。 -->
      <el-date-picker
        v-model="timeRange"
        type="datetimerange"
        value-format="YYYY-MM-DD HH:mm:ss"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        :shortcuts="dateShortcuts"
        :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
        class="admin-audit__date"
        @change="applyFilter"
      />

      <el-button type="primary" :icon="Search" :disabled="isBusy" @click="applyFilter">查询</el-button>
      <el-button v-if="hasFilter" plain :icon="Refresh" :disabled="isBusy" @click="resetFilter">
        重置
      </el-button>
    </div>

    <p class="admin-audit__note">
      只读页面：审计日志由后端在各管理操作时<b>同事务</b>写入，前端不提供任何修改/删除入口。
      时间默认留空 = 全部；时间格式固定 <span class="cm-num">YYYY-MM-DD HH:mm:ss</span>。
    </p>

    <!-- ---------------- ① loading ---------------- -->
    <div v-if="loading" class="admin-audit__skeleton">
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
      :title="hasFilter ? '没有匹配的审计记录' : '还没有审计记录'"
      :description="
        hasFilter
          ? '当前操作类型 / 时间范围内没有记录（操作类型是精确匹配，请确认选的是后端枚举里的值）。'
          : '管理员做过审核、封禁、强制退款、分类变更等操作后，这里会留下记录。'
      "
      :action-text="hasFilter ? '清空筛选条件' : ''"
      @action="resetFilter"
    />

    <!-- ---------------- ⑤ success ---------------- -->
    <template v-else>
      <p class="admin-audit__count">
        共 <b class="cm-num">{{ total }}</b> 条记录 · 按操作时间倒序
      </p>

      <el-table :data="records" row-key="id" class="admin-audit__table">
        <el-table-column label="操作人" width="130">
          <template #default="{ row }">
            <span
              class="admin-audit__operator cm-num"
              title="后端 operatorName 写入的是用户 ID 字符串（AdminAuditService:62），不是昵称"
            >
              {{ operatorOf(row) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作类型" width="150">
          <template #default="{ row }">
            <el-tag :type="tagTypeOf(auditOperationTone(row.operationType))" effect="light" round>
              {{ auditOperationLabel(row.operationType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="目标" width="130">
          <template #default="{ row }">
            <span class="admin-audit__target">
              {{ auditTargetTypeLabel(row.targetType) }}:<span class="cm-num">{{ row.targetId }}</span>
            </span>
          </template>
        </el-table-column>

        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="tagTypeOf(auditResultTone(row.result))" effect="plain" round>
              {{ auditResultLabel(row.result) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="详情" min-width="240">
          <template #default="{ row }">
            <span class="admin-audit__detail" :title="row.detail || ''">{{ row.detail || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作时间" width="170">
          <template #default="{ row }">
            <span class="admin-audit__time">{{ formatDate(row.createTime) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="showPager" class="admin-audit__pager">
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
.admin-audit {
  &__filters {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    margin-bottom: 10px;
  }

  &__select {
    width: 200px;
  }

  &__date {
    width: 420px;

    @include cm-max($cm-bp-md) {
      width: 100%;
    }
  }

  &__note {
    font-size: 12.5px;
    color: $cm-text-placeholder;
    line-height: 1.7;
    margin-bottom: 12px;
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

  &__operator {
    font-size: 13px;
    color: $cm-text;
  }

  &__target {
    font-size: 12.5px;
    color: $cm-text-secondary;
  }

  &__detail {
    font-size: 12.5px;
    color: $cm-text-secondary;
    line-height: 1.5;
    @include cm-ellipsis-lines(2);
  }

  &__time {
    font-size: 12.5px;
    color: $cm-text-secondary;
  }

  &__pager {
    display: flex;
    justify-content: center;
    margin-top: 18px;
  }
}
</style>
