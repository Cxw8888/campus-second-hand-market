import { mergeConfig } from 'vite'
import baseConfig from './vite.config.js'

/**
 * vitest 配置（在 vite.config.js 基础上追加测试相关设置）
 *
 * 三个必要的调整，都是实测踩出来的：
 *
 * 1. environment: 'jsdom'
 *    组件要真的挂载到 DOM 上才能测「连点」这类交互，Node 默认环境没有 document。
 *
 * 2. server.deps.inline: [/element-plus/]
 *    vitest 默认把 node_modules 依赖「外部化」交给 Node 原生 ESM 加载，而 Element Plus 的按需引入
 *    会 import 'element-plus/es/components/xxx/style/css'（本质是 .css 文件）——
 *    Node 原生 ESM 不认识 .css，直接报 ERR_UNKNOWN_FILE_EXTENSION。
 *    内联之后交给 Vite 处理（Vite 对 CSS 返回空模块）。
 *
 * 3. css: false
 *    测试不需要真的编译样式（SCSS 里那些设计变量对断言毫无影响），关掉能明显加快启动。
 */
export default mergeConfig(baseConfig, {
  test: {
    environment: 'jsdom',
    css: false,
    globals: false,
    // 只跑 src 下的测试，避免把 node_modules/dist 里的东西扫进来
    include: ['src/**/*.{test,spec}.js'],
    server: {
      deps: {
        inline: [/element-plus/]
      }
    }
  }
})
