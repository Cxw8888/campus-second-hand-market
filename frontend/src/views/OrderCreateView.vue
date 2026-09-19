<script setup>
/**
 * 下单页 /order/create?productId=xxx
 *
 * 交易方式是校园二手场景的重点，这里按商品的 trade_type 动态渲染：
 *   tradeType=1（仅面交）→ 只显示「面交地点」，默认带出商品的 tradeLocation，允许改
 *   tradeType=2（仅邮寄）→ 只显示「收货地址」，必填
 *   tradeType=3（皆可）  → Radio 二选一，选中后再显示对应字段
 *
 * ⚠️ 后端的 tb_order 只有一个自由文本字段 address（没有 trade_location 快照），
 *    所以本页的约定是：面交时把「约定地点」写进 address，邮寄时把「收货地址」写进 address。
 *    详情页据此按 tradeType 用不同文案展示同一个字段。
 *
 * 防重 Token 流程（接口幂等的关键）：
 *   商品详情页点「立即购买」时会先调 GET /order/token，把 token 通过 query 带过来；
 *   提交前如果 token 缺失或已放置超过 4 分钟（TTL 5 分钟，避免刚好卡在过期边界），就地重新获取。
 *   若提交返回 202（token 已被消费/失效），会立刻再取一个新 token，用户再点一次即可成功。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Location, Van, ShoppingCart } from '@element-plus/icons-vue'
import ProductImage from '@/components/ProductImage.vue'
import ConditionTag from '@/components/ConditionTag.vue'
import TradeTypeTag from '@/components/TradeTypeTag.vue'
import { getProductDetail } from '@/api/product'
import { createOrder, getOrderToken } from '@/api/order'
import { useUserStore } from '@/stores/user'
import { CODE, payTimeoutHint } from '@/utils/constants'
import { formatPrice } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const productId = computed(() => String(route.query.productId || ''))

const loading = ref(true)
const loadError = ref('')
const product = ref(null)
const formRef = ref()
const submitting = ref(false)

/** 防重 Token 及其获取时间（用于判断是否接近 5 分钟 TTL） */
const orderToken = ref(String(route.query.token || ''))
const tokenFetchedAt = ref(Date.now())

const form = reactive({
  quantity: 1,
  /** tradeType=3 时用户的选择：1-面交，2-邮寄 */
  tradeChoice: 1,
  /** 面交约定地点 */
  faceLocation: '',
  /** 邮寄收货地址 */
  mailAddress: ''
})

// ------------------------------------------------------------------ 交易方式推导
const productTradeType = computed(() => Number(product.value?.tradeType) || 0)
/** 实际生效的交易方式：1-面交 / 2-邮寄 */
const effectiveTrade = computed(() => (productTradeType.value === 3 ? form.tradeChoice : productTradeType.value))
const isFace = computed(() => effectiveTrade.value === 1)
const needFaceLocation = computed(() => isFace.value)
const needMailAddress = computed(() => !isFace.value && productTradeType.value !== 0)

const maxStock = computed(() => Math.max(1, Number(product.value?.stock) || 1))
const unitPrice = computed(() => formatPrice(product.value?.price))
const totalAmount = computed(() => formatPrice((Number(product.value?.price) || 0) * (Number(form.quantity) || 0)))

/** 商品可购买性 */
const soldOut = computed(() => Number(product.value?.stock) === 0 || Number(product.value?.status) === 2)
const offShelf = computed(() => product.value && Number(product.value.status) !== 1 && Number(product.value.status) !== 2)
/** 不能购买自己发布的商品（后端会返回 code=100，这里提前拦截，省一次失败请求） */
const isOwnProduct = computed(
  () => Boolean(userStore.userInfo?.userId) && String(product.value?.sellerId) === String(userStore.userInfo.userId)
)

// ------------------------------------------------------------------ 校验规则
const rules = computed(() => {
  const base = {
    quantity: [
      { required: true, message: '请选择购买数量', trigger: 'change' },
      {
        validator(rule, value, callback) {
          const n = Number(value)
          if (!Number.isInteger(n) || n < 1) return callback(new Error('购买数量至少为 1'))
          if (n > maxStock.value) return callback(new Error(`最多只能买 ${maxStock.value} 件`))
          return callback()
        },
        trigger: 'change'
      }
    ]
  }
  if (needFaceLocation.value) {
    base.faceLocation = [
      { required: true, message: '请填写约定的面交地点', trigger: 'blur' },
      { max: 255, message: '地点长度不能超过 255', trigger: 'blur' }
    ]
  }
  if (needMailAddress.value) {
    base.mailAddress = [
      { required: true, message: '请填写收货地址', trigger: 'blur' },
      { max: 255, message: '地址长度不能超过 255', trigger: 'blur' }
    ]
  }
  return base
})

/** 提交给后端的 address：面交=约定地点，邮寄=收货地址 */
const submitAddress = computed(() =>
  isFace.value ? form.faceLocation.trim() : form.mailAddress.trim()
)

// ------------------------------------------------------------------ 加载商品
async function loadProduct() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await getProductDetail(productId.value)
    product.value = data
    // 面交地点默认带出卖家填写的位置，用户可改
    form.faceLocation = data?.tradeLocation || ''
    // 默认交易方式：能面交就优先面交（校园二手的主流场景）
    form.tradeChoice = Number(data?.tradeType) === 2 ? 2 : 1
  } catch (error) {
    loadError.value = error?.message || '商品加载失败'
    console.warn('[order-create] 商品加载失败：', error?.message)
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------ 防重 Token
async function refreshToken() {
  try {
    const data = await getOrderToken()
    orderToken.value = data?.token || ''
    tokenFetchedAt.value = Date.now()
    return orderToken.value
  } catch (error) {
    console.warn('[order-create] 获取防重 Token 失败：', error?.message)
    return ''
  }
}

/** token 是否还「新鲜」：TTL 5 分钟，留 1 分钟安全边界 */
function isTokenFresh() {
  return Boolean(orderToken.value) && Date.now() - tokenFetchedAt.value < 4 * 60 * 1000
}

// ------------------------------------------------------------------ 提交
/** 针对下单常见错误码给出更贴合场景的提示与补救动作 */
function handleCreateError(error) {
  // 请求是 silent 的，所以提示由这里负责，不会和拦截器重复弹窗
  switch (error?.code) {
    case CODE.STOCK_NOT_ENOUGH:
      ElMessage.error('手慢了，该商品库存不足。已为你刷新最新库存，可以调小数量再试')
      loadProduct()
      break
    case CODE.REPEAT_SUBMIT:
      ElMessage.warning('这单已经提交过了，请勿重复点击。已刷新提交凭证，再点一次即可')
      refreshToken()
      break
    case CODE.NO_PERMISSION:
      ElMessage.error('无权操作该订单，请确认下单账号是否正确')
      break
    case CODE.PRODUCT_NOT_AVAILABLE:
      ElMessage.error('商品不存在或已下架，无法下单')
      loadProduct()
      break
    case CODE.PARAM_ERROR:
      ElMessage.error(error.message || '下单参数有误，请检查后重试')
      break
    default:
      // 其它错误：把后端文案透出去（例如 100「不能购买自己发布的商品」）
      ElMessage.error(error?.message || '下单失败，请稍后重试')
  }
}

/**
 * 提交订单
 *
 * ⚠️ 这里是「连点堆叠弹窗」Bug 的修复点，有一个非常关键的时序细节：
 *
 *   错误写法（Bug 版）：先 await 校验 → 再弹确认框 → 弹窗之后再置 submitting=true。
 *     从点击到弹窗出现这段时间按钮仍可点，连点几次就堆叠几个确认框。
 *
 *   半对的写法（也踩过）：把 submitting=true 提到弹窗之前，但后面还留着 `await validate()`。
 *     因为 `await` 会让出执行权，5 次点击会在任何一次置位之前全部通过入口守卫，
 *     结果依然是 5 个弹窗 —— 实测确认过。
 *
 *   正确写法（当前）：**在任何 await 之前同步上锁**。
 *     JS 是单线程的，同步代码块内不会被其它点击事件插入，
 *     所以第 1 次点击一进来就把 submitting 置为 true，后续点击在入口守卫处直接 return，
 *     连 window 事件循环都进不来 —— 这才是真正可靠的防连点。
 *
 *   解锁统一放在 finally：校验失败、用户取消、请求失败、请求成功都会解锁
 *   （成功时页面已跳走，解锁无副作用）。
 */
async function handleSubmit() {
  // ① 同步上锁：必须在任何 await 之前，否则 await 期间的重复点击会漏进来
  if (submitting.value) return
  submitting.value = true

  try {
    // ② 表单校验（此时按钮已经是 loading 态，Element Plus 的 loading 会同时禁用点击）
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return

    if (soldOut.value) {
      ElMessage.warning('该商品已售罄')
      return
    }
    if (isOwnProduct.value) {
      ElMessage.warning('不能购买自己发布的商品')
      return
    }

    // ③ 二次确认；用户点「再想想」或关闭弹窗都视为取消
    try {
      await confirmSubmit()
    } catch {
      return
    }

    // ④ 真正提交（缺失或临近过期就地重新获取 Token）
    let token = orderToken.value
    if (!isTokenFresh()) {
      token = await refreshToken()
    }

    const data = await createOrder(
      {
        productId: productId.value,
        quantity: Number(form.quantity),
        address: submitAddress.value
      },
      token,
      { silent: true }
    )

    // 支付窗口按 trade_type 分档（批次 6.0.7）：面交 120 分钟 / 邮寄 15 分钟。
    // 用【商品】维度的 tradeType 而不是上面的 effectiveTrade：订单快照取的是商品维度
    // （tradeType=3 的订单快照也是 3，后端按 IN (2,3) 走 15 分钟档），两者可能不同。
    ElMessage.success(`下单成功，${payTimeoutHint(productTradeType.value)}`)
    // replace：避免用户点浏览器返回又回到下单页重复提交
    router.replace({ name: 'order-success', params: { orderId: String(data.orderId) } })
  } catch (error) {
    handleCreateError(error)
  } finally {
    // ⑤ 直到请求返回（或中途取消）才解锁
    submitting.value = false
  }
}

/**
 * 二次确认弹窗
 *
 * beforeClose 里用了「单次关闭」标记：弹窗的确认按钮被连点时会多次触发关闭动作，
 * 这里保证只真正关闭一次，避免同一个弹窗重复走确认分支。
 *
 * 注意：beforeClose 里 **不能** 写成「有弹窗就直接 return 不 done()」——
 * 那会让弹窗永远关不掉。真正防止「多弹窗堆叠」的是上面函数入口的 submitting 守卫。
 */
function confirmSubmit() {
  let closed = false
  return ElMessageBox.confirm(
    `商品：${product.value?.title}\n` +
      `交易方式：${isFace.value ? '面交' : '邮寄'}\n` +
      `${isFace.value ? '面交地点' : '收货地址'}：${submitAddress.value}\n` +
      `数量：${form.quantity} 件　合计：¥${totalAmount.value}`,
    '确认提交订单',
    {
      confirmButtonText: '确认下单',
      cancelButtonText: '再想想',
      type: 'warning',
      // 多行文本用 pre-line 保留换行
      customClass: 'cm-confirm-box',
      beforeClose(action, instance, done) {
        if (closed) return // 已经处理过一次关闭动作（例如连点确定）：忽略后续
        closed = true
        done()
      }
    }
  )
}

function goBack() {
  router.back()
}

onMounted(loadProduct)
</script>

<template>
  <main class="order-create cm-container">
    <div class="order-create__crumb">
      <el-button link :icon="ArrowLeft" class="order-create__back" @click="goBack">返回</el-button>
      <span class="order-create__crumb-sep">/</span>
      <span class="order-create__crumb-text">确认订单</span>
    </div>

    <!-- 加载态 -->
    <div v-if="loading" class="order-create__skeleton">
      <el-skeleton :rows="6" animated />
    </div>

    <!-- 加载失败 -->
    <EmptyState
      v-else-if="loadError"
      title="商品信息加载失败"
      :description="loadError"
      action-text="返回首页"
      @action="router.push({ name: 'home' })"
    />

    <template v-else-if="product">
      <!-- ---------------- 商品摘要 ---------------- -->
      <section class="order-create__product">
        <div class="order-create__cover">
          <ProductImage :src="product.coverImage" :alt="product.title" ratio="1 / 1" :icon-size="26" />
        </div>
        <div class="order-create__product-main">
          <h1 class="order-create__title">{{ product.title }}</h1>
          <div class="order-create__product-tags">
            <ConditionTag :level="product.conditionLevel" size="sm" />
            <!-- 交易方式三色标签：面交绿 / 邮寄蓝 / 皆可橙 -->
            <TradeTypeTag :type="productTradeType" size="sm" />
          </div>
          <div class="order-create__product-price">
            <span class="cm-price">
              <span class="cm-price__symbol">¥</span>{{ unitPrice }}
            </span>
            <span class="order-create__stock">库存 {{ product.stock }} 件</span>
          </div>
        </div>
      </section>

      <!-- 不可购买的各种情况 -->
      <el-alert
        v-if="soldOut || offShelf || isOwnProduct"
        class="order-create__block-alert"
        :type="isOwnProduct ? 'warning' : 'error'"
        show-icon
        :closable="false"
        :title="
          isOwnProduct ? '不能购买自己发布的商品' : soldOut ? '该商品已售罄' : '该商品当前不可购买'
        "
      />

      <!-- ---------------- 订单表单 ---------------- -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        class="order-create__form"
      >
        <!-- 交易方式：皆可时让用户二选一 -->
        <section class="order-create__block">
          <h2 class="order-create__block-title">交易方式</h2>

          <div v-if="productTradeType === 3" class="order-create__radio">
            <el-radio-group v-model="form.tradeChoice">
              <el-radio-button :value="1">面交（校内自取）</el-radio-button>
              <el-radio-button :value="2">邮寄（快递到付/包邮）</el-radio-button>
            </el-radio-group>
            <p class="order-create__tip">该商品同时支持面交和邮寄，请选择你方便的方式</p>
          </div>
          <div v-else class="order-create__fixed-trade">
            <el-icon :size="15">
              <component :is="productTradeType === 1 ? Location : Van" />
            </el-icon>
            <span>该商品仅支持<b>{{ productTradeType === 1 ? '面交' : '邮寄' }}</b></span>
          </div>

          <!-- 面交地点 -->
          <el-form-item v-if="needFaceLocation" label="面交地点" prop="faceLocation">
            <el-input
              v-model="form.faceLocation"
              :prefix-icon="Location"
              placeholder="例如：图书馆一楼大厅 / 三教门口"
              maxlength="255"
              show-word-limit
              clearable
            />
            <p class="order-create__field-tip">
              已默认带出卖家填写的位置「{{ product.tradeLocation || '未填写' }}」，你可以改成双方约定的地点
            </p>
          </el-form-item>

          <!-- 收货地址 -->
          <el-form-item v-if="needMailAddress" label="收货地址" prop="mailAddress">
            <el-input
              v-model="form.mailAddress"
              :prefix-icon="Van"
              type="textarea"
              :rows="2"
              placeholder="例如：XX 大学 X 号宿舍楼 101 室 / 菜鸟驿站代收"
              maxlength="255"
              show-word-limit
            />
          </el-form-item>
        </section>

        <!-- 数量 -->
        <section class="order-create__block">
          <h2 class="order-create__block-title">购买数量</h2>
          <el-form-item prop="quantity" label-width="0">
            <el-input-number
              v-model="form.quantity"
              :min="1"
              :max="maxStock"
              :disabled="soldOut"
              controls-position="right"
            />
            <span class="order-create__qty-tip">最多可购买 {{ maxStock }} 件</span>
          </el-form-item>
        </section>
      </el-form>

      <!-- ---------------- 底部提交栏 ---------------- -->
      <div class="order-create__bar">
        <div class="order-create__summary">
          <span class="order-create__summary-label">
            单价 ¥{{ unitPrice }} × {{ form.quantity }} 件
          </span>
          <span class="order-create__summary-total">
            合计
            <span class="cm-price order-create__total">
              <span class="cm-price__symbol">¥</span>{{ totalAmount }}
            </span>
          </span>
        </div>

        <el-button
          class="order-create__submit"
          size="large"
          :icon="ShoppingCart"
          :loading="submitting"
          :disabled="soldOut || offShelf || isOwnProduct"
          @click="handleSubmit"
        >
          提交订单
        </el-button>
      </div>
    </template>
  </main>
</template>

<style scoped lang="scss">
.order-create {
  flex: 1;
  padding-top: 16px;
  padding-bottom: 40px;
  max-width: 860px;

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

  &__skeleton {
    @include cm-card;
    padding: 24px;
  }

  // ---------------- 商品摘要 ----------------
  &__product {
    @include cm-card;
    display: flex;
    gap: 16px;
    padding: 16px;
    margin-bottom: 16px;
  }

  &__cover {
    flex: none;
    width: 96px;
    border-radius: $cm-radius;
    overflow: hidden;
  }

  &__product-main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: $cm-text;
    line-height: 1.45;
    @include cm-ellipsis-lines(2);
  }

  &__product-tags {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  // 交易方式标签样式由 TradeTypeTag 提供（原来固定绿色，区分不出面交/邮寄）
  &__product-price {
    display: flex;
    align-items: baseline;
    gap: 12px;
    margin-top: auto;
  }

  &__stock {
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__block-alert {
    margin-bottom: 16px;
    border-radius: $cm-radius;
  }

  // ---------------- 表单区块 ----------------
  &__form {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

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

  &__radio {
    margin-bottom: 18px;
  }

  &__tip {
    margin-top: 8px;
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__fixed-trade {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 18px;
    padding: 8px 14px;
    border-radius: $cm-radius;
    font-size: 13px;
    color: $cm-primary-700;
    background: $cm-primary-50;

    b {
      font-weight: 700;
    }
  }

  &__field-tip {
    margin-top: 6px;
    font-size: 12px;
    color: $cm-text-placeholder;
    line-height: 1.6;
  }

  &__qty-tip {
    margin-left: 12px;
    font-size: 12px;
    color: $cm-text-secondary;
  }

  // ---------------- 底部提交栏 ----------------
  &__bar {
    @include cm-card;
    position: sticky;
    bottom: 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 14px 18px;
    margin-top: 16px;
    box-shadow: 0 6px 24px rgba(17, 24, 39, 0.12);
  }

  &__summary {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__summary-label {
    font-size: 12px;
    color: $cm-text-secondary;
  }

  &__summary-total {
    font-size: 13px;
    color: $cm-text;
  }

  &__total {
    @include cm-price(24px);
    margin-left: 4px;
  }

  // 提交按钮：与商品详情页「立即购买」同一套橙→金渐变
  &__submit {
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
      box-shadow 0.2s ease;

    &:hover:not(.is-disabled) {
      transform: translateY(-2px);
      box-shadow: 0 6px 18px rgba(245, 158, 11, 0.42);
    }

    &.is-disabled {
      background: #d1d5db;
      box-shadow: none;
      color: #ffffff;
    }
  }

  @include cm-max($cm-bp-sm) {
    &__bar {
      flex-direction: column;
      align-items: stretch;

      :deep(.el-button) {
        width: 100%;
      }
    }
  }
}
</style>

<style lang="scss">
/* 确认弹窗要显示多行文本，需要保留换行（放在非 scoped 里才能作用到 body 下的弹窗 DOM） */
.cm-confirm-box .el-message-box__message p {
  white-space: pre-line;
  line-height: 1.8;
}
</style>
