<script setup>
/**
 * 管理端 · 分类管理（/admin/category）
 *
 * ===================== 后端契约（全部已读源码核实，不凭印象） =====================
 * · 列表：GET /api/v1/category/list（**可选认证**，CategoryController.java:32-35）
 *     → `List<CategoryVO>`，按 sort 升序 + id 升序，**不分页**
 *     ⚠️ CategoryVO 只有 id / name / sort（CategoryVO.java:18-24），
 *        实体也只有 id / name / sort（Category.java:22-31）：
 *        - **没有 parentId** → 所以是**扁平列表**，不做树形、不做父分类筛选；
 *        - **没有 icon / color / description** → 不显示这些列（不塞假字段）；
 *        - **没有 productCount** → 关联商品数的取数方式见下。
 * · 新增：POST   /api/v1/admin/category        body {name, sort}       → {id}
 * · 编辑：PUT    /api/v1/admin/category/{id}   body {name, sort}
 * · 删除：DELETE /api/v1/admin/category/{id}   → 分类下有商品时 **code=208**（CategoryServiceImpl.java:118-122）
 * · 迁移：PUT    /api/v1/admin/category/migrate body {fromCategoryId, toCategoryId} → {movedCount}
 *     → **情况 A：后端有批量迁移接口**，删除遇 208 时走「选目标分类 → 迁移 → 自动删除」的弹窗引导
 *
 * ===================== 校验（源码核实） =====================
 * · name：@NotBlank + @Size(max=50)（CategorySaveRequest.java:19-22）；后端先 trim 再查重，
 *   重复 → **code=100「分类名称已存在」**（CategoryServiceImpl.java:77-80），且 DB 有 `uk_category_name`
 *   唯一索引（V1__init.sql:51）→ 前端**不做**查重预检（后端 + DB 双重兜住，预检反而有并发窗口），
 *   只把 100 的文案交给拦截器提示，并保持弹窗不关（用户可直接改名重试）。
 * · sort：后端**没有任何 @Min/@Max**，null 兜 0（CategoryServiceImpl.java:83/104）
 *   → `ADMIN_CATEGORY_SORT_MIN/MAX`（0~9999）是**纯前端兜底**，已在 constants.js 注明。
 *
 * ===================== 关联商品数从哪来 =====================
 * CategoryVO 没有 productCount，也没有专用的计数接口。但管理员调
 * `GET /product/list?categoryId=x&size=1` 时可见性是「全部状态」
 * （ProductServiceImpl.checkVisibility:398 `if (UserContext.isAdmin())` 直接放行），
 * 其 `total` 与后端 208 判定所用的口径**一致**（CategoryServiceImpl.delete:118-120 就是
 * `selectCount(categoryId=id)`，MyBatis-Plus 自动过滤 is_deleted）→ 用它取真实数量。
 * 每个分类一次轻量请求（size=1 只要总数）；**取不到就显示「—」，绝不写 0**。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import { createCategory, deleteCategory, migrateCategory, updateCategory } from '@/api/admin'
import { getCategoryList, getProductList } from '@/api/product'
import {
  ADMIN_CATEGORY_SORT_MAX,
  ADMIN_CATEGORY_SORT_MIN,
  CATEGORY_NAME_MAX,
  CODE
} from '@/utils/constants'

const router = useRouter()

const loading = ref(true)
const categories = ref([])
/** 分类 id → 关联商品数（null 表示没取到，显示「—」而不是 0） */
const counts = ref({})
const errorMessage = ref('')
const forbidden = ref(false)

/** 行级+操作级同步锁：`${id}:${action}`（创建用 'create'） */
const busyKey = ref('')

// ---------------- 新增 / 编辑弹窗 ----------------
const dialogVisible = ref(false)
const dialogMode = ref('create')
const editing = ref(null)
/** 提交锁：必须在第一个 await 之前同步置 true */
const submitting = ref(false)
const formRef = ref(null)
const form = reactive({ name: '', sort: 0 })
const rules = {
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: CATEGORY_NAME_MAX, message: `分类名称不能超过 ${CATEGORY_NAME_MAX} 个字符`, trigger: 'blur' }
  ],
  sort: [
    { required: true, type: 'number', message: '请输入排序权重', trigger: 'change' },
    {
      type: 'integer',
      min: ADMIN_CATEGORY_SORT_MIN,
      max: ADMIN_CATEGORY_SORT_MAX,
      message: `排序需为 ${ADMIN_CATEGORY_SORT_MIN} ~ ${ADMIN_CATEGORY_SORT_MAX} 的整数`,
      trigger: 'change'
    }
  ]
}

// ---------------- 迁移弹窗（208 引导） ----------------
const migrateVisible = ref(false)
const migrateFrom = ref(null)
const migrateTo = ref('')
const migrating = ref(false)

const isBusy = computed(() => busyKey.value !== '')
const isEmpty = computed(
  () => !loading.value && !errorMessage.value && !forbidden.value && categories.value.length === 0
)
const migrateTargets = computed(() =>
  categories.value.filter((item) => item.id !== migrateFrom.value?.id)
)
const migrateCountText = computed(() => {
  const from = migrateFrom.value
  if (!from) return '该分类下仍有商品'
  const count = counts.value[from.id]
  return count == null ? '该分类下仍有商品（数量未知）' : `该分类下有 ${count} 件商品`
})

const keyOf = (row, action) => `${row?.id}:${action}`
const busyOn = (row, action) => busyKey.value === keyOf(row, action)
const countOf = (row) => (row?.id in counts.value ? counts.value[row.id] : null)

// ------------------------------------------------------------------ 加载
async function fetchCategories() {
  loading.value = true
  errorMessage.value = ''
  forbidden.value = false
  try {
    const list = await getCategoryList({ silent: true })
    categories.value = Array.isArray(list) ? list : []
    await fetchCounts()
  } catch (error) {
    console.warn('[admin-category] 分类列表加载失败：', error?.message)
    categories.value = []
    counts.value = {}
    if (error?.code === CODE.FORBIDDEN) {
      forbidden.value = true
      return
    }
    errorMessage.value = error?.message || '网络异常或服务不可用'
  } finally {
    loading.value = false
  }
}

/** 逐个分类取关联商品数（size=1 只要 total）；单个失败只影响那一行（显示「—」） */
async function fetchCounts() {
  const entries = await Promise.all(
    categories.value.map(async (item) => {
      try {
        const data = await getProductList({
          categoryId: item.id,
          page: 1,
          size: 1,
          silent: true
        })
        return [item.id, Number(data?.total ?? 0)]
      } catch (error) {
        console.warn('[admin-category] 取分类商品数失败：', item.id, error?.message)
        return [item.id, null]
      }
    })
  )
  counts.value = Object.fromEntries(entries)
}

// ------------------------------------------------------------------ 弹窗
function resetForm() {
  form.name = ''
  form.sort = 0
  editing.value = null
  // 关闭时清空校验状态，避免下次打开还挂着上一次的红字
  formRef.value?.clearValidate?.()
}

function openCreate() {
  if (isBusy.value) return
  dialogMode.value = 'create'
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  if (isBusy.value) return
  dialogMode.value = 'edit'
  editing.value = { id: row.id, name: row.name }
  form.name = row.name
  // sort 是后端下发的数字（不是 id），可以安全当数字用
  form.sort = Number(row.sort ?? 0)
  dialogVisible.value = true
}

async function submitDialog() {
  if (submitting.value) return
  submitting.value = true // 同步锁：务必在第一个 await 之前
  try {
    await formRef.value.validate()
    const payload = { name: String(form.name).trim(), sort: Number(form.sort) }
    if (dialogMode.value === 'create') {
      await createCategory(payload)
      ElMessage.success('分类已创建')
    } else {
      await updateCategory(editing.value.id, payload)
      ElMessage.success('分类已更新')
    }
    dialogVisible.value = false
    await fetchCategories()
  } catch (error) {
    // 表单校验失败时 error 是字段错误对象（没有 code），不当作业务错误处理
    if (error && error.code !== undefined) {
      handleActionError(error)
    } else {
      console.warn('[admin-category] 表单校验未通过')
    }
  } finally {
    submitting.value = false
  }
}

// ------------------------------------------------------------------ 操作
/**
 * 统一错误处理
 * · 403 → 切无权限态
 * · 209 / 204 / 203 → 列表数据已过期 → 静默刷新 + info（不弹"操作失败"）
 * · 其余（如 100 分类名重复）→ 拦截器已提示，这里只留控制台
 * ⚠️ 208 不走这里：它需要**弹窗引导**而不是静默刷新（见 handleDelete）
 */
function handleActionError(error) {
  if (error?.code === CODE.FORBIDDEN) {
    forbidden.value = true
    return
  }
  if (
    error?.code === CODE.STATUS_NOT_ALLOWED ||
    error?.code === CODE.PRODUCT_NOT_AVAILABLE ||
    error?.code === CODE.NO_PERMISSION
  ) {
    fetchCategories()
    ElMessage.info('列表已更新')
    return
  }
  console.warn('[admin-category] 操作失败：', error?.message || error)
}

async function handleDelete(row) {
  if (isBusy.value) return
  busyKey.value = keyOf(row, 'delete')
  try {
    const count = counts.value[row.id]
    await ElMessageBox.confirm(
      count == null
        ? '删除后该分类会从发布页与首页筛选中消失，且不可恢复。'
        : `该分类下有 ${count} 件商品时后端会拒绝删除；确认删除「${row.name}」？`,
      `确认删除「${row.name}」？`,
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
        customClass: 'cm-confirm-box'
      }
    )
    await deleteCategory(row.id)
    ElMessage.success('分类已删除')
    await fetchCategories()
  } catch (error) {
    if (error?.code === CODE.CATEGORY_HAS_PRODUCT) {
      // 208：分类下还有商品 → 弹迁移引导（用户需要看到引导，不能静默刷新）
      openMigrate(row)
    } else {
      handleActionError(error)
    }
  } finally {
    busyKey.value = ''
  }
}

function openMigrate(row) {
  migrateFrom.value = { id: row.id, name: row.name }
  migrateTo.value = ''
  migrateVisible.value = true
}

/** 迁移 → 成功后自动删除源分类（情况 A 的完整流程） */
async function confirmMigrate() {
  if (migrating.value || !migrateFrom.value) return
  if (!migrateTo.value) {
    ElMessage.warning('请先选择要迁移到的目标分类')
    return
  }
  migrating.value = true // 同步锁
  try {
    const from = migrateFrom.value
    const result = await migrateCategory(from.id, migrateTo.value)
    // 迁移完成后该分类下已无商品，直接删掉它（否则还要管理员手动再来一次）
    await deleteCategory(from.id)
    const moved = Number(result?.movedCount ?? 0)
    ElMessage.success(
      moved > 0 ? `已迁移 ${moved} 件商品，并删除分类「${from.name}」` : `分类「${from.name}」已删除`
    )
    migrateVisible.value = false
    await fetchCategories()
  } catch (error) {
    handleActionError(error)
  } finally {
    migrating.value = false
  }
}

function goHome() {
  router.push({ name: 'home' })
}

onMounted(fetchCategories)
</script>

<template>
  <section class="admin-category">
    <!-- ---------------- 工具栏 ---------------- -->
    <div class="admin-category__toolbar">
      <div>
        <h2 class="admin-category__title">商品分类</h2>
        <p class="admin-category__sub">
          分类为扁平结构（后端 Category 只有 id / name / sort，没有父级）；数值越小的分类排越前。
        </p>
      </div>
      <div class="admin-category__actions">
        <el-button :icon="Refresh" plain :disabled="isBusy" @click="fetchCategories">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="isBusy" @click="openCreate">新增分类</el-button>
      </div>
    </div>

    <!-- ---------------- ① loading ---------------- -->
    <div v-if="loading" class="admin-category__skeleton">
      <el-skeleton :rows="5" animated />
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
      @action="fetchCategories"
    />

    <!-- ---------------- ④ 空列表 ---------------- -->
    <EmptyState
      v-else-if="isEmpty"
      title="还没有任何分类"
      description="分类是商品发布时的必选项，建议先补上学校常用的几类（教材书籍 / 数码电子 / 生活用品 等）。"
      action-text="新增第一个分类"
      @action="openCreate"
    />

    <!-- ---------------- ⑤ success ---------------- -->
    <template v-else>
      <p class="admin-category__count">
        共 <b class="cm-num">{{ categories.length }}</b> 个分类 ·
        <span class="admin-category__hint">该接口不分页，一次返回全部（按排序权重升序）</span>
      </p>

      <el-table :data="categories" row-key="id" class="admin-category__table">
        <el-table-column label="分类名" min-width="220">
          <template #default="{ row }">
            <span class="admin-category__name">{{ row.name }}</span>
          </template>
        </el-table-column>

        <el-table-column label="排序权重" width="120">
          <template #default="{ row }">
            <span class="cm-num">{{ row.sort }}</span>
          </template>
        </el-table-column>

        <el-table-column label="关联商品数" width="140">
          <template #default="{ row }">
            <span v-if="countOf(row) !== null" class="admin-category__count-num cm-num">
              {{ countOf(row) }} 件
            </span>
            <span v-else class="admin-category__unknown" title="后端未返回数量（CategoryVO 无 productCount）">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" align="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              plain
              size="small"
              :disabled="isBusy"
              :loading="busyOn(row, 'edit')"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              plain
              type="danger"
              size="small"
              :disabled="isBusy"
              :loading="busyOn(row, 'delete')"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- ---------------- 新增 / 编辑弹窗 ---------------- -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增分类' : `编辑分类「${editing?.name ?? ''}」`"
      width="460px"
      :close-on-click-modal="false"
      destroy-on-close
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="86px" @submit.prevent>
        <el-form-item label="分类名称" prop="name">
          <el-input
            v-model="form.name"
            :maxlength="CATEGORY_NAME_MAX"
            show-word-limit
            placeholder="如：教材书籍"
          />
        </el-form-item>

        <el-form-item label="排序权重" prop="sort">
          <el-input-number
            v-model="form.sort"
            :min="ADMIN_CATEGORY_SORT_MIN"
            :max="ADMIN_CATEGORY_SORT_MAX"
            :step="1"
            step-strictly
            controls-position="right"
            class="admin-category__sort-input"
          />
          <span class="admin-category__field-hint">
            整数，越小越靠前（后端无范围约束，{{ ADMIN_CATEGORY_SORT_MIN }} ~ {{ ADMIN_CATEGORY_SORT_MAX }} 为前端兜底）
          </span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submitDialog">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- ---------------- 208 迁移引导弹窗 ---------------- -->
    <el-dialog
      v-model="migrateVisible"
      title="该分类下还有商品，先迁移再删除"
      width="500px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        :title="`${migrateCountText}，无法直接删除`"
        description="后端在分类下存在商品时会拒绝删除（code=208）。选择目标分类后，商品会整体迁移过去，随后自动删除当前分类。"
      />

      <el-form label-width="86px" class="admin-category__migrate-form">
        <el-form-item label="迁移到">
          <el-select v-model="migrateTo" placeholder="请选择目标分类" class="admin-category__select">
            <el-option
              v-for="item in migrateTargets"
              :key="item.id"
              :label="`${item.name}（排序 ${item.sort}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <p class="admin-category__migrate-tip">
        迁移会同步失效相关商品详情缓存，前端无需额外操作。
      </p>

      <template #footer>
        <el-button :disabled="migrating" @click="migrateVisible = false">取消</el-button>
        <el-button type="primary" :loading="migrating" :disabled="migrating" @click="confirmMigrate">
          迁移并删除
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped lang="scss">
.admin-category {
  &__toolbar {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
    margin-bottom: 14px;
  }

  &__title {
    font-size: 15px;
    font-weight: 700;
    color: $cm-text;
  }

  &__sub {
    font-size: 12.5px;
    color: $cm-text-placeholder;
    margin-top: 4px;
  }

  &__actions {
    display: flex;
    gap: 8px;
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

  &__hint,
  &__unknown {
    color: $cm-text-placeholder;
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

  &__name {
    font-size: 13.5px;
    font-weight: 600;
    color: $cm-text;
  }

  &__count-num {
    color: $cm-text;
  }

  &__sort-input {
    width: 140px;
  }

  &__field-hint {
    margin-left: 10px;
    font-size: 11.5px;
    color: $cm-text-placeholder;
    line-height: 1.5;
  }

  &__migrate-form {
    margin-top: 16px;
  }

  &__select {
    width: 100%;
  }

  &__migrate-tip {
    font-size: 12px;
    color: $cm-text-secondary;
    line-height: 1.7;
  }
}
</style>
