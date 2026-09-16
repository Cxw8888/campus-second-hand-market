/**
 * Pinia 实例
 *
 * 单独建一个文件导出实例（而不是在 main.js 里 createPinia），
 * 是为了让 store / router 守卫 / 测试都能 import 到同一个实例，避免多实例串状态。
 */
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'

const pinia = createPinia()

// 持久化插件：让 stores/user.js 里声明的 persist 配置生效
pinia.use(piniaPluginPersistedstate)

export default pinia
