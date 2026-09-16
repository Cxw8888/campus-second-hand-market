<script setup>
/**
 * 个人中心 /user/profile
 *
 * 四块内容：快捷入口 / 基本信息 / 修改密码 / 换绑邮箱。
 *
 * ⚠️ 强制登出（本页最重要的行为）：
 *   修改密码、换绑邮箱成功后，后端会把该用户的 token version +1，**所有旧 Token 立即失效**。
 *   所以不能只是 router.push('/login') 就完事 —— 那样 Pinia 里还留着 token/userInfo，
 *   路由守卫会认为「已登录」，用户刷新一下又被弹回个人中心，状态彻底错乱。
 *   正确做法是：提示 → 清 Pinia + 清 localStorage → 再跳登录页。
 *
 *   这里调的是 store 的 reset()（而不是 logout()）：logout() 会先请求后端 /auth/logout，
 *   但此刻 Token 已被后端作废，那次请求必然 401，只会多弹一个「登录已失效」并额外触发一次跳转。
 *   reset() 做的正是我们需要的两件事：清 Pinia 状态 + 清 localStorage 里的 token。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Star, Bell, List, Goods, Camera, Message, Lock, Key, Iphone } from '@element-plus/icons-vue'
import ProductImage from '@/components/ProductImage.vue'
import { sendEmailCode } from '@/api/auth'
import { changeEmail, changePassword, getProfile, updateProfile } from '@/api/user'
import { getFavoriteList } from '@/api/favorite'
import { getMyProducts } from '@/api/product'
import { uploadImage } from '@/api/upload'
import { useUserStore } from '@/stores/user'
import { useNotificationStore } from '@/stores/notification'
import { compressImage, formatBytes, isImageFile } from '@/utils/image'
import { avatarText, formatDate } from '@/utils/format'
import { adminRoleLabel } from '@/utils/constants'

const router = useRouter()
const userStore = useUserStore()
/** 未读数统一读全局 store（轮询在 App.vue 里跑），保证与导航栏角标一致 */
const notificationStore = useNotificationStore()

// ------------------------------------------------------------------ 基础数据
const loadingProfile = ref(true)
const profile = ref(null)
const favoriteCount = ref(0)
/** 我发布的商品数（含待审核/下架，与「我的商品」页「全部」Tab 口径一致） */
const myProductCount = ref(0)

const CAMPUS_EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@(stu|campus)\.edu\.cn$/
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,20}$/

/** 角色文案统一走 constants.js 的字典（5.3 顺手修掉这里的硬编码）；
 *  资料未加载完时不显示（避免把"未知"说成"学生"） */
const roleText = computed(() => (profile.value ? adminRoleLabel(profile.value.role) : ''))

/**
 * 快捷入口（收藏 / 消息 / 订单 / 我的发布）
 *
 * 四个入口覆盖学生**既买也卖**的两个身份：买 => 收藏+订单，卖 => 我的发布+订单（卖家视角）。
 * 除订单外都带实时数量角标，让学生一眼看到"有没有待办"。
 */
const shortcuts = computed(() => [
  {
    key: 'favorite',
    title: '我的收藏',
    desc: '收藏过的闲置物品',
    icon: Star,
    count: favoriteCount.value,
    to: { name: 'favorite-list' }
  },
  {
    key: 'notification',
    title: '消息中心',
    desc: '订单与审核通知',
    icon: Bell,
    count: notificationStore.unreadCount,
    to: { name: 'notification-list' }
  },
  {
    key: 'order',
    title: '我的订单',
    desc: '买到的与卖出的',
    icon: List,
    count: null,
    to: { name: 'order-list' }
  },
  {
    key: 'product',
    title: '我的发布',
    desc: '管理已发布的闲置',
    icon: Goods,
    count: myProductCount.value,
    to: { name: 'product-my' }
  }
])

async function loadProfile() {
  loadingProfile.value = true
  try {
    const data = await getProfile()
    profile.value = data
    // 同步进 store，导航栏的昵称/头像会立刻更新
    userStore.patchUserInfo(data)
  } catch (error) {
    console.warn('[profile] 加载个人资料失败：', error?.message)
  } finally {
    loadingProfile.value = false
  }
}

/**
 * 三个角标：拿不到就保持 0，不打扰用户
 *   · 收藏数：/favorite/list 的 total（只取 size=1 的轻量请求）
 *   · 未读数：走全局 store（顺带校准一次，因为它每 30 秒才轮询一次）
 *   · 我的发布数：/product/my 的 total（不传 status 即"全部"，与我的商品页口径一致）
 */
async function loadBadges() {
  const [fav, mine] = await Promise.all([
    getFavoriteList({ page: 1, size: 1 }).catch(() => null),
    getMyProducts({ page: 1, size: 1 }).catch(() => null)
  ])
  favoriteCount.value = Number(fav?.total ?? 0)
  myProductCount.value = Number(mine?.total ?? 0)
  await notificationStore.refresh()
}

// ------------------------------------------------------------------ 资料：昵称 + 头像
const nicknameDraft = ref('')
const savingNickname = ref(false)
const uploadingAvatar = ref(false)

function syncDrafts() {
  nicknameDraft.value = profile.value?.nickname || ''
}

async function handleSaveNickname() {
  if (savingNickname.value) return
  const nickname = nicknameDraft.value.trim()
  if (!nickname) {
    ElMessage.warning('昵称不能为空')
    return
  }
  if (nickname.length > 50) {
    ElMessage.warning('昵称不能超过 50 个字')
    return
  }
  if (nickname === (profile.value?.nickname || '')) {
    ElMessage.info('昵称没有变化')
    return
  }

  savingNickname.value = true
  try {
    await updateProfile({ nickname }, { silent: true })
    profile.value = { ...profile.value, nickname }
    userStore.patchUserInfo({ nickname })
    ElMessage.success('昵称已更新')
  } catch (error) {
    ElMessage.error(error?.message || '昵称更新失败')
  } finally {
    savingNickname.value = false
  }
}

/** 头像上传：复用与商品图同一套 canvas 压缩 */
async function handleAvatarUpload(options) {
  const file = options.file
  if (!isImageFile(file)) {
    ElMessage.error('请选择图片文件')
    return
  }

  uploadingAvatar.value = true
  try {
    const compressed = await compressImage(file, { maxEdge: 512, quality: 0.85 })
    const data = await uploadImage(compressed, {})
    if (!data?.url) throw new Error('上传接口未返回图片地址')

    await updateProfile({ avatar: data.url }, { silent: true })
    profile.value = { ...profile.value, avatar: data.url }
    userStore.patchUserInfo({ avatar: data.url })

    ElMessage.success(
      compressed === file
        ? '头像已更新'
        : `头像已更新（${formatBytes(file.size)} → ${formatBytes(compressed.size)}）`
    )
  } catch (error) {
    console.warn('[profile] 头像更新失败：', error?.message)
    if (!error?.code) ElMessage.error(error?.message || '头像更新失败')
  } finally {
    uploadingAvatar.value = false
  }
}

// ------------------------------------------------------------------ 强制重新登录
/**
 * 修改密码 / 换绑邮箱成功后调用
 * 顺序很重要：先弹提示，用户确认后再清状态，最后跳登录页
 */
async function forceRelogin(message) {
  await ElMessageBox.alert(message, '操作成功', {
    confirmButtonText: '去重新登录',
    type: 'success',
    showClose: false
  })
  // reset() = 清 Pinia(token/userInfo) + 清 localStorage 的 token
  userStore.reset()
  router.replace({ name: 'login' })
}

// ------------------------------------------------------------------ 修改密码
const passwordFormRef = ref()
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const savingPassword = ref(false)

const passwordRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        if (!value) return callback()
        if (!PASSWORD_PATTERN.test(value)) {
          return callback(new Error('新密码需 8-20 位，且同时包含字母、数字和特殊字符'))
        }
        if (value === passwordForm.oldPassword) {
          return callback(new Error('新密码不能与当前密码相同'))
        }
        return callback()
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        if (value && value !== passwordForm.newPassword) {
          return callback(new Error('两次输入的新密码不一致'))
        }
        return callback()
      },
      trigger: 'blur'
    }
  ]
}

async function handleChangePassword() {
  if (savingPassword.value) return
  savingPassword.value = true
  try {
    const valid = await passwordFormRef.value?.validate().catch(() => false)
    if (!valid) return

    await changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })

    await forceRelogin('密码修改成功，为保证账号安全，请使用新密码重新登录。')
  } catch (error) {
    ElMessage.error(error?.message || '密码修改失败')
  } finally {
    savingPassword.value = false
  }
}

// ------------------------------------------------------------------ 换绑邮箱
const emailFormRef = ref()
const emailForm = reactive({ newEmail: '', emailCode: '', password: '' })
const savingEmail = ref(false)
const sendingCode = ref(false)
const countdown = ref(0)
let timer = null

const codeButtonText = computed(() => {
  if (sendingCode.value) return '发送中'
  if (countdown.value > 0) return `${countdown.value} 秒后重试`
  return '获取验证码'
})

const emailRules = {
  newEmail: [
    { required: true, message: '请输入新邮箱', trigger: 'blur' },
    {
      validator(rule, value, callback) {
        if (!value) return callback()
        if (!CAMPUS_EMAIL_PATTERN.test(value)) {
          return callback(new Error('请使用校园邮箱（@stu.edu.cn 或 @campus.edu.cn）'))
        }
        if (value === profile.value?.email) {
          return callback(new Error('新邮箱不能与当前邮箱相同'))
        }
        return callback()
      },
      trigger: 'blur'
    }
  ],
  emailCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ],
  // 后端 ChangeEmailRequest 确实要求登录密码，少了直接 code=100
  password: [{ required: true, message: '请输入当前密码以确认身份', trigger: 'blur' }]
}

function startCountdown() {
  countdown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function handleSendCode() {
  const email = emailForm.newEmail?.trim()
  if (!email) {
    ElMessage.warning('请先填写新邮箱')
    return
  }
  if (!CAMPUS_EMAIL_PATTERN.test(email)) {
    ElMessage.warning('请使用校园邮箱（@stu.edu.cn 或 @campus.edu.cn）')
    return
  }

  sendingCode.value = true
  try {
    // scene=BIND_EMAIL：换绑场景
    const data = await sendEmailCode(email, 'BIND_EMAIL')
    startCountdown()
    if (data?.skip && data?.code) {
      // 降级模式（app.email.skip=true）：验证码直接返回，顺手回填便于演示
      emailForm.emailCode = data.code
      ElMessage.success(`演示模式：验证码 ${data.code} 已自动填入`)
    } else {
      ElMessage.success('验证码已发送至新邮箱，请注意查收')
    }
  } catch (error) {
    // 105 邮件发送失败 / 106 发送过于频繁 / 102 邮箱格式 —— 拦截器已提示
    console.warn('[profile] 获取验证码失败：', error?.message)
  } finally {
    sendingCode.value = false
  }
}

async function handleChangeEmail() {
  if (savingEmail.value) return
  savingEmail.value = true
  try {
    const valid = await emailFormRef.value?.validate().catch(() => false)
    if (!valid) return

    await changeEmail({
      newEmail: emailForm.newEmail.trim(),
      emailCode: emailForm.emailCode.trim(),
      password: emailForm.password
    })

    await forceRelogin('邮箱换绑成功，请使用新邮箱对应的账号信息重新登录。')
  } catch (error) {
    ElMessage.error(error?.message || '换绑邮箱失败')
  } finally {
    savingEmail.value = false
  }
}

onMounted(async () => {
  await loadProfile()
  syncDrafts()
  loadBadges()
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <main class="profile cm-container">
    <header class="profile__header">
      <h1 class="profile__title">个人中心</h1>
      <p class="profile__sub">管理你的资料、账号安全与消息</p>
    </header>

    <!-- ---------------- 快捷入口 ---------------- -->
    <section class="profile__shortcuts">
      <button
        v-for="item in shortcuts"
        :key="item.key"
        class="profile__shortcut"
        @click="router.push(item.to)"
      >
        <span class="profile__shortcut-icon">
          <el-icon :size="20"><component :is="item.icon" /></el-icon>
        </span>
        <span class="profile__shortcut-main">
          <span class="profile__shortcut-title">{{ item.title }}</span>
          <span class="profile__shortcut-desc">{{ item.desc }}</span>
        </span>
        <span v-if="item.count" class="profile__shortcut-badge cm-num">
          {{ item.count > 99 ? '99+' : item.count }}
        </span>
      </button>
    </section>

    <!-- ---------------- 基本信息 ---------------- -->
    <section class="profile__card">
      <h2 class="profile__card-title">基本信息</h2>

      <div v-if="loadingProfile" class="profile__loading">
        <el-skeleton :rows="3" animated />
      </div>

      <template v-else>
        <div class="profile__avatar-row">
          <!-- 头像上传：复用商品图那套压缩逻辑，边长压到 512 即可 -->
          <el-upload
            class="profile__avatar-upload"
            :show-file-list="false"
            accept="image/jpeg,image/png,image/webp"
            :http-request="handleAvatarUpload"
          >
            <div class="profile__avatar" :class="{ 'is-loading': uploadingAvatar }">
              <ProductImage
                v-if="profile?.avatar"
                :src="profile.avatar"
                alt="头像"
                ratio="1 / 1"
                :icon-size="20"
              />
              <span v-else class="profile__avatar-text">{{ avatarText(profile) }}</span>

              <span class="profile__avatar-mask">
                <el-icon :size="18"><Camera /></el-icon>
              </span>
            </div>
          </el-upload>

          <div class="profile__avatar-tip">
            <p class="profile__avatar-title">点击头像即可更换</p>
            <p class="profile__avatar-desc">支持 jpg / png / webp，上传前会自动压缩</p>
          </div>
        </div>

        <dl class="profile__rows">
          <div class="profile__row">
            <dt>学号 / 账号</dt>
            <dd>{{ profile?.username || '—' }}</dd>
          </div>
          <div class="profile__row">
            <dt>校园邮箱</dt>
            <dd>{{ profile?.email || '—' }}</dd>
          </div>
          <div class="profile__row">
            <dt>账号角色</dt>
            <dd>
              <span class="profile__role" :class="{ 'is-admin': Number(profile?.role) === 1 }">
                {{ roleText }}
              </span>
            </dd>
          </div>
          <!-- 后端 UserVO 目前不下发 createTime，拿不到就整行不渲染，避免出现「—」这种残缺感 -->
          <div v-if="profile?.createTime" class="profile__row">
            <dt>注册时间</dt>
            <dd>{{ formatDate(profile.createTime) }}</dd>
          </div>
        </dl>

        <div class="profile__nickname">
          <span class="profile__nickname-label">昵称</span>
          <el-input
            v-model="nicknameDraft"
            placeholder="给自己起个昵称"
            maxlength="50"
            show-word-limit
            clearable
            class="profile__nickname-input"
          />
          <el-button type="primary" :loading="savingNickname" @click="handleSaveNickname">
            保存昵称
          </el-button>
        </div>
      </template>
    </section>

    <!-- ---------------- 修改密码 ---------------- -->
    <section class="profile__card">
      <h2 class="profile__card-title">
        <el-icon :size="15"><Key /></el-icon>
        修改密码
      </h2>
      <p class="profile__card-tip">修改成功后所有登录状态会失效，需要用新密码重新登录。</p>

      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-position="top"
        size="large"
        class="profile__form"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input
            v-model="passwordForm.oldPassword"
            type="password"
            :prefix-icon="Lock"
            placeholder="请输入当前密码"
            show-password
          />
        </el-form-item>

        <div class="profile__form-row">
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="passwordForm.newPassword"
              type="password"
              :prefix-icon="Key"
              placeholder="8-20 位，含字母/数字/特殊字符"
              show-password
            />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="passwordForm.confirmPassword"
              type="password"
              :prefix-icon="Key"
              placeholder="请再次输入新密码"
              show-password
            />
          </el-form-item>
        </div>

        <el-button type="primary" :loading="savingPassword" @click="handleChangePassword">
          确认修改密码
        </el-button>
      </el-form>
    </section>

    <!-- ---------------- 换绑邮箱 ---------------- -->
    <section class="profile__card">
      <h2 class="profile__card-title">
        <el-icon :size="15"><Iphone /></el-icon>
        换绑校园邮箱
      </h2>
      <p class="profile__card-tip">
        当前邮箱：<b>{{ profile?.email || '未绑定' }}</b>。换绑成功后同样需要重新登录。
      </p>

      <el-form
        ref="emailFormRef"
        :model="emailForm"
        :rules="emailRules"
        label-position="top"
        size="large"
        class="profile__form"
      >
        <el-form-item label="新邮箱" prop="newEmail">
          <el-input
            v-model="emailForm.newEmail"
            :prefix-icon="Message"
            placeholder="20210001@stu.edu.cn"
            clearable
          />
        </el-form-item>

        <el-form-item label="邮箱验证码" prop="emailCode">
          <div class="profile__code-row">
            <el-input v-model="emailForm.emailCode" placeholder="6 位数字验证码" maxlength="6" clearable />
            <el-button
              class="profile__code-btn"
              :disabled="countdown > 0 || sendingCode"
              :loading="sendingCode"
              @click="handleSendCode"
            >
              {{ codeButtonText }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="当前密码" prop="password">
          <el-input
            v-model="emailForm.password"
            type="password"
            :prefix-icon="Lock"
            placeholder="换绑属于敏感操作，需验证当前密码"
            show-password
          />
        </el-form-item>

        <el-button type="primary" :loading="savingEmail" @click="handleChangeEmail">
          确认换绑邮箱
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<style scoped lang="scss">
.profile {
  flex: 1;
  padding-top: 24px;
  padding-bottom: 56px;
  max-width: 880px;

  &__header {
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

  // ---------------- 快捷入口 ----------------
  // 快捷入口从 3 个扩到 4 个（多了「我的发布」），用 auto-fit 让它自适应换行，
  // 不必再为不同断点手写列数
  &__shortcuts {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
    gap: 14px;
    margin-bottom: 16px;
  }

  &__shortcut {
    @include cm-card;
    @include cm-hover-lift(-3px);
    position: relative;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 16px;
    border: none;
    text-align: left;
    cursor: pointer;
    font-family: inherit;
  }

  &__shortcut-icon {
    @include cm-center;
    flex: none;
    width: 42px;
    height: 42px;
    border-radius: $cm-radius;
    color: $cm-primary;
    background: $cm-primary-50;
  }

  &__shortcut-main {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  &__shortcut-title {
    font-size: 14px;
    font-weight: 600;
    color: $cm-text;
  }

  &__shortcut-desc {
    font-size: 11px;
    color: $cm-text-placeholder;
    @include cm-ellipsis;
  }

  &__shortcut-badge {
    margin-left: auto;
    flex: none;
    min-width: 22px;
    height: 22px;
    padding: 0 7px;
    border-radius: $cm-radius-pill;
    background: $cm-accent;
    color: #ffffff;
    font-size: 11px;
    font-weight: 700;
    line-height: 22px;
    text-align: center;
  }

  // ---------------- 卡片 ----------------
  &__card {
    @include cm-card;
    padding: 20px 22px;
    margin-bottom: 16px;
  }

  &__card-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 15px;
    font-weight: 700;
    color: $cm-text;
    margin-bottom: 8px;
    padding-left: 10px;
    border-left: 3px solid $cm-primary;
    line-height: 1.2;
  }

  &__card-tip {
    font-size: 12px;
    color: $cm-text-secondary;
    margin-bottom: 16px;
    line-height: 1.7;

    b {
      color: $cm-text;
    }
  }

  &__loading {
    padding: 8px 0;
  }

  // ---------------- 头像 ----------------
  &__avatar-row {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 20px;
  }

  &__avatar-upload {
    :deep(.el-upload) {
      display: block;
    }
  }

  &__avatar {
    position: relative;
    width: 76px;
    height: 76px;
    border-radius: 50%;
    overflow: hidden;
    background: $cm-gradient-brand;
    cursor: pointer;

    &.is-loading {
      opacity: 0.65;
    }

    :deep(.cm-image) {
      height: 100%;
    }
  }

  &__avatar-text {
    @include cm-center;
    width: 100%;
    height: 100%;
    font-size: 28px;
    font-weight: 700;
    color: #ffffff;
  }

  &__avatar-mask {
    position: absolute;
    inset: 0;
    @include cm-center;
    color: #ffffff;
    background: rgba(17, 24, 39, 0.45);
    opacity: 0;
    transition: opacity 0.2s ease;
  }

  &__avatar:hover &__avatar-mask {
    opacity: 1;
  }

  &__avatar-title {
    font-size: 14px;
    font-weight: 600;
    color: $cm-text;
    margin-bottom: 4px;
  }

  &__avatar-desc {
    font-size: 12px;
    color: $cm-text-placeholder;
  }

  // ---------------- 信息行 ----------------
  &__rows {
    border-top: 1px dashed $cm-border;
    padding-top: 4px;
    margin-bottom: 20px;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 10px 0;
    border-bottom: 1px dashed $cm-border-light;

    &:last-child {
      border-bottom: none;
    }

    dt {
      flex: none;
      font-size: 13px;
      color: $cm-text-secondary;
    }

    dd {
      margin: 0;
      font-size: 13px;
      font-weight: 600;
      color: $cm-text;
      text-align: right;
      word-break: break-all;
    }
  }

  &__role {
    padding: 2px 10px;
    border-radius: $cm-radius-pill;
    font-size: 12px;
    font-weight: 600;
    color: $cm-primary-700;
    background: $cm-primary-50;

    &.is-admin {
      color: $cm-accent-dark;
      background: $cm-accent-50;
    }
  }

  // ---------------- 昵称 ----------------
  &__nickname {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__nickname-label {
    flex: none;
    font-size: 13px;
    color: $cm-text-secondary;
  }

  &__nickname-input {
    flex: 1;
    max-width: 320px;
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
  }

  &__form-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
  }

  &__code-row {
    display: flex;
    gap: 10px;
    width: 100%;
  }

  &__code-btn {
    flex: none;
    min-width: 122px;
    border-radius: $cm-radius;
  }

  @include cm-max($cm-bp-md) {
    &__shortcuts {
      grid-template-columns: 1fr;
    }

    &__form-row {
      grid-template-columns: 1fr;
      gap: 0;
    }

    &__nickname {
      flex-wrap: wrap;
    }

    &__nickname-input {
      max-width: none;
    }
  }
}
</style>
