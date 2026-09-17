<script setup>
/**
 * 商品表单（发布页与编辑页共用）
 *
 * 为什么抽成独立组件：发布和编辑的字段、校验规则、联动逻辑**完全一样**，
 * 唯一差别是编辑页要回填已有数据、并且提交时多一个 id。分成两个页面各写一遍必然走样。
 *
 * 对外接口（父组件通过 template ref 调用）：
 *   · form          表单数据对象（测试里也用它直接灌数据，避免去戳 DOM）
 *   · validate()    触发校验，返回 boolean
 *   · getPayload()  产出后端 ProductSaveRequest 需要的**全量**字段
 *   · setValues(p)  用商品详情回填（编辑页用）
 *
 * 交易方式与面交地点的联动（校园二手场景的关键）：
 *   tradeType=1 仅面交 → 面交地点必填
 *   tradeType=2 仅邮寄 → 面交地点隐藏（值也不提交，避免脏数据）
 *   tradeType=3 皆可   → 面交地点可选
 *
 * 分类数据（批次 5.4）：来自 stores/category.js（接口实时数据 + 本地缓存 + CATEGORIES 兜底）。
 *   挂载时**不 await**，下拉先用 store 当前数据渲染，后台请求回来后自动重渲染。
 *   管理员删掉某个分类时，**绝不自动清空 form.categoryId**（那会把用户已填的表单内容一起弄丢）：
 *   改为在表单里提示「分类已失效」，并在提交前拦截（见 validate()）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ImageUploader from '@/components/ImageUploader.vue'
import { useCategoryStore } from '@/stores/category'
import {
  CONDITION_OPTIONS,
  MAX_PRODUCT_IMAGES,
  PRODUCT_DESCRIPTION_MAX,
  PRODUCT_TITLE_MAX,
  TRADE_LOCATION_MAX
} from '@/utils/constants'
import { formatPrice } from '@/utils/format'

const props = defineProps({
  /** 编辑页传入商品详情用于回填；发布页不传 */
  initial: { type: Object, default: null },
  /** 提交中（父组件控制，用于禁用整个表单） */
  submitting: { type: Boolean, default: false }
})

const formRef = ref()

const categoryStore = useCategoryStore()

/** 分类下拉数据（只认 store.getList()，字段差异/兜底逻辑全在 store 里） */
const categories = computed(() => categoryStore.getList())

const form = reactive({
  title: '',
  description: '',
  categoryId: null,
  price: null,
  stock: 1,
  conditionLevel: null,
  tradeType: null,
  tradeLocation: '',
  imageUrls: []
})

/** 交易方式选项 */
const TRADE_OPTIONS = [
  { value: 1, label: '仅面交', desc: '校内见面交易，最省事' },
  { value: 2, label: '仅邮寄', desc: '快递寄送，需填收货地址' },
  { value: 3, label: '皆可', desc: '买家下单时自己选' }
]

const needTradeLocation = computed(() => form.tradeType === 1)
const showTradeLocation = computed(() => form.tradeType !== 2)
const tradeLocationOptional = computed(() => form.tradeType === 3)

/** 小计预估：纯展示，帮用户确认价格没填错 */
const pricePreview = computed(() => (form.price ? formatPrice(form.price) : '—'))

/**
 * 选中的分类是否已不在当前列表里（管理员删了 / 换了分类）
 *
 * 比较时统一 String()：分类 id 全程是字符串，但历史数据（商品详情回填、
 * 组件外部直接赋值）有可能是数字，用字符串比较可以避免「1 !== '1' 被误判为失效」。
 */
const categoryInvalid = computed(() => {
  if (form.categoryId == null || form.categoryId === '') return false
  return !categories.value.some((c) => String(c.id) === String(form.categoryId))
})

const rules = computed(() => ({
  title: [
    { required: true, message: '请填写商品标题', trigger: 'blur' },
    { min: 1, max: PRODUCT_TITLE_MAX, message: `标题长度需在 1-${PRODUCT_TITLE_MAX} 字之间`, trigger: 'blur' }
  ],
  description: [
    { max: PRODUCT_DESCRIPTION_MAX, message: `描述不能超过 ${PRODUCT_DESCRIPTION_MAX} 字`, trigger: 'blur' }
  ],
  categoryId: [{ required: true, message: '请选择商品分类', trigger: 'change' }],
  price: [
    { required: true, message: '请填写商品价格', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        const n = Number(value)
        if (!Number.isFinite(n) || n <= 0) return callback(new Error('价格必须大于 0'))
        return callback()
      },
      trigger: 'blur'
    }
  ],
  stock: [
    { required: true, message: '请填写库存数量', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        const n = Number(value)
        if (!Number.isInteger(n) || n < 0) return callback(new Error('库存不能小于 0'))
        return callback()
      },
      trigger: 'blur'
    }
  ],
  conditionLevel: [{ required: true, message: '请选择商品成色', trigger: 'change' }],
  tradeType: [{ required: true, message: '请选择交易方式', trigger: 'change' }],
  // 联动校验：只有「仅面交」才强制要求地点
  tradeLocation: needTradeLocation.value
    ? [
        { required: true, message: '选择「仅面交」时，面交地点必填', trigger: 'blur' },
        { max: TRADE_LOCATION_MAX, message: `面交地点不能超过 ${TRADE_LOCATION_MAX} 字`, trigger: 'blur' }
      ]
    : [{ max: TRADE_LOCATION_MAX, message: `面交地点不能超过 ${TRADE_LOCATION_MAX} 字`, trigger: 'blur' }],
  imageUrls: [
    {
      validator(rule, value, callback) {
        if (!value || value.length === 0) return callback(new Error('请至少上传 1 张商品图片'))
        if (value.length > MAX_PRODUCT_IMAGES) {
          return callback(new Error(`最多上传 ${MAX_PRODUCT_IMAGES} 张图片`))
        }
        return callback()
      },
      trigger: 'change'
    }
  ]
}))

/**
 * 触发校验；父组件据此决定是否真正提交
 *
 * 除 Element Plus 的字段校验外，这里追加一道**分类存在性**校验：
 * 分类可能在用户填写期间被管理员删除/迁移，此时后端会返回 code=100，
 * 与其让用户提交后看一个看不懂的报错，不如在前端先拦住并说清原因。
 */
async function validate() {
  if (!formRef.value) return false
  const passed = await formRef.value.validate().catch(() => false)
  if (!passed) return false

  if (categoryInvalid.value) {
    ElMessage.error('您选择的分类已失效，请重新选择')
    return false
  }
  return true
}

/**
 * 产出提交载荷（全量字段）
 * 注意：仅邮寄（tradeType=2）时把 tradeLocation 置空，避免把「面交地点」这种无用字段带到后端
 */
function getPayload() {
  return {
    categoryId: form.categoryId,
    title: form.title.trim(),
    description: form.description?.trim() || '',
    price: Number(form.price),
    stock: Number(form.stock),
    conditionLevel: form.conditionLevel,
    tradeType: form.tradeType,
    tradeLocation: form.tradeType === 2 ? '' : (form.tradeLocation || '').trim(),
    imageUrls: [...form.imageUrls]
  }
}

/** 编辑页回填 */
function setValues(product) {
  if (!product) return
  form.title = product.title || ''
  form.description = product.description || ''
  // 分类 id 全程字符串（后端 Long→String），与下拉选项的取值保持同一类型，
  // 否则 el-select 会因为 1 !== '1' 而显示成「未选中」（编辑页回填失效）
  form.categoryId = product.categoryId != null ? String(product.categoryId) : null
  form.price = product.price != null ? Number(product.price) : null
  form.stock = product.stock != null ? Number(product.stock) : 1
  form.conditionLevel = product.conditionLevel != null ? Number(product.conditionLevel) : null
  form.tradeType = product.tradeType != null ? Number(product.tradeType) : null
  form.tradeLocation = product.tradeLocation || ''
  form.imageUrls = Array.isArray(product.imageUrls)
    ? [...product.imageUrls]
    : product.coverImage
      ? [product.coverImage]
      : []
}

/** 清空（发布页提交失败后重试、或「继续发布」时用） */
function reset() {
  form.title = ''
  form.description = ''
  form.categoryId = null
  form.price = null
  form.stock = 1
  form.conditionLevel = null
  form.tradeType = null
  form.tradeLocation = ''
  form.imageUrls = []
  formRef.value?.clearValidate()
}

// 组件内直接回填一次（编辑页传了 initial 的情况）
if (props.initial) setValues(props.initial)

// 首屏不阻塞：不 await，分类请求在后台跑，下拉先用 store 当前数据（兜底/缓存）渲染
onMounted(() => {
  categoryStore.ensureLoaded()
})

defineExpose({ form, validate, getPayload, setValues, reset, formRef })
</script>

<template>
  <el-form
    ref="formRef"
    :model="form"
    :rules="rules"
    :disabled="submitting"
    label-position="top"
    size="large"
    class="product-form"
  >
    <!-- ---------------- 基本信息 ---------------- -->
    <section class="product-form__block">
      <h2 class="product-form__block-title">基本信息</h2>

      <el-form-item label="商品标题" prop="title">
        <el-input
          v-model="form.title"
          placeholder="例如：考研数学复习全书 九成新"
          :maxlength="PRODUCT_TITLE_MAX"
          show-word-limit
          clearable
        />
      </el-form-item>

      <el-form-item label="商品描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="4"
          placeholder="说说成色、使用情况、附赠配件、交易时间地点等，信息越全越容易卖出去"
          :maxlength="PRODUCT_DESCRIPTION_MAX"
          show-word-limit
        />
      </el-form-item>

      <div class="product-form__row">
        <el-form-item label="商品分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择分类" class="product-form__select">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="价格（元）" prop="price">
          <el-input-number
            v-model="form.price"
            :min="0.01"
            :max="999999"
            :precision="2"
            :step="1"
            controls-position="right"
            placeholder="0.00"
            class="product-form__number"
          />
          <span class="product-form__hint">买家看到：¥{{ pricePreview }}</span>
        </el-form-item>

        <el-form-item label="库存（件）" prop="stock">
          <el-input-number
            v-model="form.stock"
            :min="0"
            :max="9999"
            :step="1"
            :precision="0"
            controls-position="right"
            class="product-form__number"
          />
        </el-form-item>
      </div>

      <!-- 分类失效提示：管理员删掉该分类后才出现。刻意**不清空** form.categoryId，
           否则用户已经填好的其它字段体验会被打断（见 validate() 里的提交拦截） -->
      <el-alert
        v-if="categoryInvalid"
        class="product-form__category-alert"
        type="warning"
        show-icon
        :closable="false"
        title="您选择的分类已失效，请重新选择"
      />
    </section>

    <!-- ---------------- 成色 ---------------- -->
    <section class="product-form__block">
      <h2 class="product-form__block-title">商品成色</h2>
      <el-form-item prop="conditionLevel" label-width="0">
        <el-radio-group v-model="form.conditionLevel" class="product-form__radios">
          <el-radio v-for="opt in CONDITION_OPTIONS" :key="opt.value" :value="opt.value" border>
            {{ opt.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
    </section>

    <!-- ---------------- 交易方式 ---------------- -->
    <section class="product-form__block">
      <h2 class="product-form__block-title">交易方式</h2>
      <el-form-item prop="tradeType" label-width="0">
        <el-radio-group v-model="form.tradeType" class="product-form__trade">
          <el-radio v-for="opt in TRADE_OPTIONS" :key="opt.value" :value="opt.value" border>
            <span class="product-form__trade-label">{{ opt.label }}</span>
            <span class="product-form__trade-desc">{{ opt.desc }}</span>
          </el-radio>
        </el-radio-group>
      </el-form-item>

      <!-- 联动：仅邮寄时整块隐藏 -->
      <el-form-item v-if="showTradeLocation" label="面交地点" prop="tradeLocation">
        <el-input
          v-model="form.tradeLocation"
          placeholder="例如：图书馆一楼大厅 / 三教门口"
          :maxlength="TRADE_LOCATION_MAX"
          show-word-limit
          clearable
        />
        <p class="product-form__hint">
          <template v-if="needTradeLocation">
            「仅面交」必须填写地点，买家下单时会默认带出这里填的位置
          </template>
          <template v-else> 选填：填了的话，买家选择面交时会默认带出这个地点 </template>
          <template v-if="tradeLocationOptional"> （当前为选填）</template>
        </p>
      </el-form-item>
    </section>

    <!-- ---------------- 图片 ---------------- -->
    <section class="product-form__block">
      <h2 class="product-form__block-title">商品图片</h2>
      <el-form-item prop="imageUrls" label-width="0">
        <ImageUploader v-model="form.imageUrls" :max="MAX_PRODUCT_IMAGES" />
      </el-form-item>
    </section>
  </el-form>
</template>

<style scoped lang="scss">
.product-form {
  display: flex;
  flex-direction: column;
  gap: 16px;

  &__block {
    @include cm-card;
    padding: 20px 22px 8px;
  }

  &__block-title {
    font-size: 15px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 16px;
    padding-left: 10px;
    border-left: 3px solid $cm-primary;
    line-height: 1.2;
  }

  &__row {
    display: grid;
    grid-template-columns: 1.2fr 1fr 1fr;
    gap: 16px;
  }

  &__select,
  &__number {
    width: 100%;
  }

  &__hint {
    margin-top: 6px;
    font-size: 12px;
    color: $cm-text-placeholder;
    line-height: 1.6;
  }

  // 分类失效提示：贴在「基本信息」区块底部，与上方表单保持同一呼吸感
  &__category-alert {
    margin: 4px 0 12px;
  }

  &__radios,
  &__trade {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;

    :deep(.el-radio) {
      margin-right: 0;
    }
  }

  &__trade {
    :deep(.el-radio) {
      height: auto;
      padding: 10px 16px;
      align-items: flex-start;
    }

    :deep(.el-radio__label) {
      display: flex;
      flex-direction: column;
      line-height: 1.4;
    }
  }

  &__trade-label {
    font-weight: 600;
  }

  &__trade-desc {
    font-size: 11px;
    color: $cm-text-placeholder;
  }

  @include cm-max($cm-bp-md) {
    &__row {
      grid-template-columns: 1fr;
      gap: 0;
    }
  }
}
</style>
