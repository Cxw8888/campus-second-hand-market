<script setup>
/**
 * 登录 / 注册页（/login、/register 共用本组件，用 props.mode 决定默认 Tab）
 *
 * 两个关键设计点：
 *
 * 1) 验证码「可见化」
 *    后端 app.email.skip=true（毕设降级）时，GET /auth/email-code 会把验证码直接放在
 *    data.code 里返回，根本不发邮件。所以这里做两件事：
 *      · 用醒目的绿色提示条把验证码显示出来（答辩演示时一眼能看到）
 *      · 顺手回填到输入框，减少现场手输出错
 *    若 skip=false（真实发信），则不显示提示条，只提示「已发送至邮箱」。
 *
 * 2) 60 秒倒计时
 *    后端对同一邮箱有 60 秒发送限流（命中返回 code=106），前端倒计时就是它的镜像，
 *    避免用户狂点导致报错。组件卸载时必须清掉定时器，否则会内存泄漏。
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User, Message, ChatDotRound, CircleCheck } from '@element-plus/icons-vue'
import AuthBrandPanel from '@/components/AuthBrandPanel.vue'
import { register as registerApi, sendEmailCode } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  /** 'login' | 'register' */
  mode: { type: String, default: 'login' }
})

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// ------------------------------------------------------------------ Tab
const activeMode = computed(() => (props.mode === 'register' ? 'register' : 'login'))

function switchMode(mode) {
  if (mode === activeMode.value) return
  // 用 replace 而不是 push：来回切 Tab 不该在浏览器历史里堆一长串记录
  router.replace({ name: mode })
}

// ------------------------------------------------------------------ 表单
const loginFormRef = ref()
const registerFormRef = ref()

const loginForm = reactive({
  username: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  email: '',
  emailCode: ''
})

const submitting = ref(false)

/** 密码规则与后端 PasswordValidator 对齐：8-20 位，含字母、数字、至少 1 个特殊字符 */
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,20}$/

/** 校园邮箱后缀（与后端 app.email.campus-suffixes 默认值一致；最终以后端校验为准） */
const CAMPUS_EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@(stu|campus)\.edu\.cn$/

const loginRules = {
  username: [{ required: true, message: '请输入学号 / 用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [
    { required: true, message: '请输入学号 / 用户名', trigger: 'blur' },
    { max: 50, message: '学号长度不能超过 50', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        if (!value) return callback()
        if (!PASSWORD_PATTERN.test(value)) {
          return callback(new Error('密码需 8-20 位，且同时包含字母、数字和特殊字符'))
        }
        return callback()
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        if (value && value !== registerForm.password) {
          return callback(new Error('两次输入的密码不一致'))
        }
        return callback()
      },
      trigger: 'blur'
    }
  ],
  nickname: [{ max: 50, message: '昵称长度不能超过 50', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入校园邮箱', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        if (!value) return callback()
        if (!CAMPUS_EMAIL_PATTERN.test(value)) {
          return callback(new Error('请使用校园邮箱（@stu.edu.cn 或 @campus.edu.cn）'))
        }
        return callback()
      },
      trigger: 'blur'
    }
  ],
  emailCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ]
}

// ------------------------------------------------------------------ 获取验证码 + 倒计时
const COUNTDOWN_SECONDS = 60

const countdown = ref(0)
const sendingCode = ref(false)
/** 降级模式下后端直接返回的验证码 */
const degradedCode = ref('')

let timer = null

const codeButtonText = computed(() => {
  if (sendingCode.value) return '发送中'
  if (countdown.value > 0) return `${countdown.value} 秒后重试`
  return '获取验证码'
})

const codeButtonDisabled = computed(() => countdown.value > 0 || sendingCode.value)

function startCountdown() {
  countdown.value = COUNTDOWN_SECONDS
  stopTimer()
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) stopTimer()
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function handleSendCode() {
  const email = registerForm.email?.trim()
  if (!email) {
    ElMessage.warning('请先填写校园邮箱')
    return
  }
  if (!CAMPUS_EMAIL_PATTERN.test(email)) {
    ElMessage.warning('请使用校园邮箱（@stu.edu.cn 或 @campus.edu.cn）')
    return
  }

  sendingCode.value = true
  try {
    const data = await sendEmailCode(email, 'REGISTER')
    startCountdown()

    if (data?.skip && data?.code) {
      // 降级模式：把验证码显示出来 + 自动回填，答辩现场免手输
      degradedCode.value = data.code
      registerForm.emailCode = data.code
      ElMessage.success('演示模式：验证码已直接返回')
    } else {
      degradedCode.value = ''
      ElMessage.success('验证码已发送至你的校园邮箱，请注意查收')
    }
  } catch (error) {
    // 业务错误（102 邮箱格式 / 106 发送过于频繁 / 105 发送失败）已由 axios 拦截器弹过提示，
    // 这里只需保证不把倒计时打开即可
    console.warn('[auth] 获取验证码失败：', error?.message)
  } finally {
    sendingCode.value = false
  }
}

/** 点验证码提示条可复制（演示时很方便） */
async function copyCode() {
  if (!degradedCode.value) return
  try {
    await navigator.clipboard.writeText(degradedCode.value)
    ElMessage.success('验证码已复制')
  } catch {
    ElMessage.info(`验证码：${degradedCode.value}`)
  }
}

// 切到登录页时收起降级提示，避免信息串场
watch(activeMode, (mode) => {
  if (mode === 'login') degradedCode.value = ''
})

// ------------------------------------------------------------------ 提交
/**
 * 登录成功后的跳转目标：
 *   优先回跳到被拦截前的页面（?redirect=/order/create），否则回首页
 */
const redirectTarget = computed(() => {
  const target = route.query.redirect
  return typeof target === 'string' && target.startsWith('/') ? target : '/'
})

async function handleLogin() {
  const form = loginFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await userStore.login({
      username: loginForm.username.trim(),
      password: loginForm.password
    })
    ElMessage.success(`欢迎回来，${userStore.displayName}`)
    router.replace(redirectTarget.value)
  } catch (error) {
    // 101 用户名或密码错误 / 104 账号锁定 15 分钟 / 205 用户封禁 —— 拦截器已提示
    console.warn('[auth] 登录失败：', error?.message)
  } finally {
    submitting.value = false
  }
}

async function handleRegister() {
  const form = registerFormRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await registerApi({
      username: registerForm.username.trim(),
      password: registerForm.password,
      nickname: registerForm.nickname?.trim() || undefined,
      email: registerForm.email.trim(),
      emailCode: registerForm.emailCode.trim()
    })
    ElMessage.success('注册成功，请使用刚设置的账号登录')
    // 把用户名带到登录表单，省一次手输
    loginForm.username = registerForm.username.trim()
    switchMode('login')
  } catch (error) {
    // 103 验证码错误或已过期 / 102 邮箱格式 / 100 参数校验 —— 拦截器已提示
    console.warn('[auth] 注册失败：', error?.message)
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="auth">
    <!-- 页面顶部：一条细的品牌绿渐变装饰条 -->
    <span class="auth__topline" aria-hidden="true" />

    <!-- 左：品牌插画区 -->
    <AuthBrandPanel class="auth__brand" />

    <!-- 右：表单区 -->
    <section class="auth__panel">
      <div class="auth__panel-inner">
        <header class="auth__header">
          <h2 class="auth__welcome">
            {{ activeMode === 'login' ? '欢迎回来 👋' : '加入我们 🌱' }}
          </h2>
          <p class="auth__sub">
            {{
              activeMode === 'login'
                ? '登录后即可收藏商品、下单交易'
                : '用校园邮箱注册，和同校同学放心交易'
            }}
          </p>
        </header>

        <!-- Tab 切换 -->
        <div class="auth__tabs" role="tablist">
          <button
            class="auth__tab"
            :class="{ 'is-active': activeMode === 'login' }"
            role="tab"
            :aria-selected="activeMode === 'login'"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            class="auth__tab"
            :class="{ 'is-active': activeMode === 'register' }"
            role="tab"
            :aria-selected="activeMode === 'register'"
            @click="switchMode('register')"
          >
            注册
          </button>
          <span class="auth__tab-indicator" :class="{ 'is-right': activeMode === 'register' }" />
        </div>

        <!-- ---------------- 登录表单 ---------------- -->
        <el-form
          v-if="activeMode === 'login'"
          ref="loginFormRef"
          :model="loginForm"
          :rules="loginRules"
          label-position="top"
          size="large"
          class="auth__form"
          @submit.prevent="handleLogin"
        >
          <el-form-item label="学号 / 用户名" prop="username">
            <el-input v-model="loginForm.username" :prefix-icon="User" placeholder="例如 20210001" clearable />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              :prefix-icon="Lock"
              placeholder="请输入密码"
              show-password
              @keyup.enter="handleLogin"
            />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="auth__submit"
            :loading="submitting"
            @click="handleLogin"
          >
            登录
          </el-button>

          <p class="auth__switch-hint">
            还没有账号？
            <a class="auth__link" @click="switchMode('register')">立即注册</a>
          </p>
        </el-form>

        <!-- ---------------- 注册表单 ---------------- -->
        <el-form
          v-else
          ref="registerFormRef"
          :model="registerForm"
          :rules="registerRules"
          label-position="top"
          size="large"
          class="auth__form"
          @submit.prevent="handleRegister"
        >
          <div class="auth__row">
            <el-form-item label="学号 / 用户名" prop="username">
              <el-input v-model="registerForm.username" :prefix-icon="User" placeholder="例如 20210001" clearable />
            </el-form-item>

            <el-form-item label="昵称（可选）" prop="nickname">
              <el-input v-model="registerForm.nickname" :prefix-icon="ChatDotRound" placeholder="同学怎么称呼你" clearable />
            </el-form-item>
          </div>

          <div class="auth__row">
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                :prefix-icon="Lock"
                placeholder="8-20 位，含字母/数字/特殊字符"
                show-password
              />
            </el-form-item>

            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                :prefix-icon="Lock"
                placeholder="请再次输入密码"
                show-password
              />
            </el-form-item>
          </div>

          <el-form-item label="校园邮箱" prop="email">
            <el-input v-model="registerForm.email" :prefix-icon="Message" placeholder="20210001@stu.edu.cn" clearable />
          </el-form-item>

          <el-form-item label="邮箱验证码" prop="emailCode">
            <div class="auth__code-row">
              <el-input
                v-model="registerForm.emailCode"
                placeholder="6 位数字验证码"
                maxlength="6"
                clearable
              />
              <el-button
                class="auth__code-btn"
                :disabled="codeButtonDisabled"
                :loading="sendingCode"
                @click="handleSendCode"
              >
                {{ codeButtonText }}
              </el-button>
            </div>
          </el-form-item>

          <!-- 降级模式提示条：email.skip=true 时后端直接把验证码返回来 -->
          <transition name="cm-fade">
            <div v-if="degradedCode" class="auth__degraded" role="status" @click="copyCode">
              <el-icon class="auth__degraded-icon" :size="18"><CircleCheck /></el-icon>
              <div class="auth__degraded-text">
                <span class="auth__degraded-title">演示模式：验证码已直接返回</span>
                <span class="auth__degraded-sub">已自动填入下方输入框，点击此处可复制</span>
              </div>
              <code class="auth__degraded-code">{{ degradedCode }}</code>
            </div>
          </transition>

          <el-button
            type="primary"
            size="large"
            class="auth__submit"
            :loading="submitting"
            @click="handleRegister"
          >
            注册
          </el-button>

          <p class="auth__switch-hint">
            已经有账号了？
            <a class="auth__link" @click="switchMode('login')">去登录</a>
          </p>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.auth {
  position: relative;
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(460px, 560px);
  min-height: 100vh;
  background: $cm-bg;

  &__topline {
    position: absolute;
    inset: 0 0 auto 0;
    height: 4px;
    background: $cm-gradient-topbar;
    z-index: 3;
  }

  &__brand {
    min-height: 100vh;
  }

  &__panel {
    @include cm-center;
    padding: 48px 40px;
    background: $cm-surface;
  }

  &__panel-inner {
    width: 100%;
    max-width: 430px;
  }

  &__header {
    margin-bottom: 22px;
  }

  &__welcome {
    font-size: 24px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 6px;
  }

  &__sub {
    font-size: 13px;
    color: $cm-text-secondary;
  }

  // ---------------- Tab ----------------
  &__tabs {
    position: relative;
    display: flex;
    gap: 28px;
    border-bottom: 1px solid $cm-border;
    margin-bottom: 24px;
  }

  &__tab {
    position: relative;
    padding: 0 2px 12px;
    border: none;
    background: transparent;
    font-size: 16px;
    font-weight: 600;
    color: $cm-text-placeholder;
    cursor: pointer;
    transition: color 0.2s ease;

    &:hover {
      color: $cm-text-secondary;
    }

    &.is-active {
      color: $cm-primary;
    }
  }

  // 滑动指示条：两个 Tab 等宽时用位移表达切换
  &__tab-indicator {
    position: absolute;
    bottom: -1px;
    left: 0;
    width: 26px;
    height: 2.5px;
    border-radius: 2px;
    background: $cm-primary;
    transition: transform 0.24s cubic-bezier(0.4, 0, 0.2, 1);

    &.is-right {
      transform: translateX(54px);
    }
  }

  // ---------------- 表单 ----------------
  &__form {
    :deep(.el-form-item) {
      margin-bottom: 18px;
    }

    :deep(.el-form-item__label) {
      font-size: 13px;
      font-weight: 500;
      color: $cm-text;
      padding-bottom: 4px;
    }

    :deep(.el-input__wrapper) {
      border-radius: $cm-radius;
    }
  }

  &__row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px;
  }

  &__code-row {
    display: flex;
    gap: 10px;
    width: 100%;
  }

  &__code-btn {
    flex: none;
    min-width: 118px;
    border-radius: $cm-radius;
  }

  &__submit {
    width: 100%;
    height: 46px;
    margin-top: 6px;
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 4px;
    border-radius: $cm-radius;
    box-shadow: 0 4px 14px rgba(16, 185, 129, 0.28);
  }

  // ---------------- 降级提示条 ----------------
  &__degraded {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 14px;
    margin-bottom: 16px;
    border-radius: $cm-radius;
    background: $cm-primary-50;
    border: 1px solid rgba($cm-primary, 0.35);
    cursor: pointer;
    transition: background 0.18s ease;

    &:hover {
      background: $cm-primary-100;
    }
  }

  &__degraded-icon {
    color: $cm-primary;
    flex: none;
  }

  &__degraded-text {
    display: flex;
    flex-direction: column;
    line-height: 1.35;
    min-width: 0;
  }

  &__degraded-title {
    font-size: 13px;
    font-weight: 600;
    color: $cm-primary-700;
  }

  &__degraded-sub {
    font-size: 11px;
    color: $cm-text-secondary;
  }

  &__degraded-code {
    margin-left: auto;
    flex: none;
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 22px;
    font-weight: 800;
    letter-spacing: 3px;
    color: $cm-primary-700;
  }

  &__switch-hint {
    margin-top: 18px;
    text-align: center;
    font-size: 13px;
    color: $cm-text-secondary;
  }

  &__link {
    color: $cm-primary;
    font-weight: 600;
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }

  // ---------------- 响应式 ----------------
  @include cm-max($cm-bp-md) {
    grid-template-columns: 1fr;

    &__brand {
      min-height: auto;
    }

    &__panel {
      padding: 32px 22px 48px;
    }

    &__row {
      grid-template-columns: 1fr;
      gap: 0;
    }
  }
}
</style>
