import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import pinia from './stores'

// 全局样式（含 Element Plus 主题变量覆盖）
import '@/assets/styles/index.scss'

/**
 * ⚠️ 必须显式引入「函数式」组件的样式（否则弹窗会跑到屏幕左下角）
 *
 * 背景：本项目用 unplugin-auto-import + unplugin-vue-components 做 Element Plus 按需引入。
 * 这个机制只对「**未声明就直接使用**」的标识符生效 —— 它会自动补 import 并连带补样式。
 *
 * 但 ElMessage / ElMessageBox 是函数式调用，业务代码里是第一类写法：
 *     import { ElMessage, ElMessageBox } from 'element-plus'
 * **显式 import 会绕过解析器**，于是 `message/style/css` 与 `message-box/style/css`
 * 从来不会被注入 —— 构建产物里完全没有这两套 CSS。
 *
 * 后果（真实踩过）：
 *   `.el-overlay-message-box` 少了 `position:fixed; inset:0; text-align:center`，
 *   `.el-message-box` 少了 `display:inline-block; vertical-align:middle; max-width:420px`，
 *   于是确认弹窗退化成追加在 body 末尾的**无样式静态块** —— 钉在屏幕左下角、
 *   标题贴着左边缘、按钮挤在最底部；ElMessage 的 toast 同样无样式。
 *
 * 注意：显式 import 的是样式入口（而不是整个 dist/index.css），这样仍然是按需的 ——
 * message-box 的 style 入口内部会自动带上 base / overlay / input / button，无需逐个补。
 *
 * 维护提醒：以后再显式 import 别的 EP 函数式 API（如 ElNotification / ElLoading），
 * 也必须在这里补上对应的 `element-plus/es/components/<name>/style/css`。
 */
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

const app = createApp(App)

// 顺序有讲究：pinia 必须早于 router。
// 因为路由守卫里会用 useUserStore()，而 useUserStore() 需要已安装的 pinia 实例。
app.use(pinia)
app.use(router)

/**
 * 全局兜底错误处理：把组件里没接住的异常打到控制台，
 * 避免答辩现场「页面白屏但控制台无声」的排查困境。
 */
app.config.errorHandler = (err, instance, info) => {
  console.error('[全局异常]', info, err)
}

app.mount('#app')
