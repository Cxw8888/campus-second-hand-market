import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Vite 配置
 *
 * 三个关键点：
 *  1. 代理：前端跑在 5173，接口在 8080。把 /api 代理过去之后，浏览器只跟 5173 通信，
 *     同源请求，**完全绕开 CORS**（后端虽然也放行了 5173，但代理更省事、也不怕 cookie/预检问题）。
 *  2. /static 也要代理：商品图片存的是相对路径（如 /static/uploads/demo1.jpg），
 *     不代理的话会被解析成 http://localhost:5173/static/... 而 404，列表页整片空白。
 *  3. Element Plus 按需自动引入：组件与 ElMessage 这类 API 都由 resolver 自动 import（含样式），
 *     所以业务代码里不需要手写 import，也不需要全量引入 element-plus/dist/index.css。
 */
export default defineConfig({
  plugins: [
    vue(),

    // 自动引入 Vue / Vue Router / Pinia 的 API（ref、computed、useRouter…），减少样板 import
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [
        // ElMessage / ElMessageBox / ElLoading 这类“函数式”组件也走按需引入（连样式一起）
        ElementPlusResolver()
      ],
      // 纯 JS 项目，不生成 .d.ts
      dts: false,
      eslintrc: { enabled: false }
    }),

    // 模板里写 <el-button> 即自动按需引入对应组件 + 样式
    Components({
      resolvers: [ElementPlusResolver()],
      dts: false
    })
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  css: {
    preprocessorOptions: {
      scss: {
        // Vite 5.4+ 支持 modern-compiler API，避免 sass 1.80+ 的 legacy 警告刷屏
        api: 'modern-compiler',
        /**
         * 给每个 .scss / <style lang="scss"> 自动注入设计变量，业务里直接用 $cm-primary 即可。
         *
         * 这里必须用「函数形式」而不是纯字符串：字符串形式会把 @use 也注入到
         * variables.scss 自己头上，于是变成 variables @use variables —— sass 会直接报
         * `Module loop: this module is already being loaded`。
         * （实测：字符串形式下 vite build 仍然能过，因为没有任何文件「直接」import 它；
         *   但只要有人 import 一次，或者 IDE 去解析这个文件，就会炸。所以这里显式跳过。）
         */
        additionalData: (source, filename) => {
          const normalized = String(filename).replace(/\\/g, '/')
          if (normalized.endsWith('src/assets/styles/variables.scss')) return source
          return `@use "@/assets/styles/variables.scss" as *;\n${source}`
        }
      }
    }
  },

  build: {
    rollupOptions: {
      output: {
        /**
         * 分包：把 ECharts 单独拆成一个 chunk（批次 5.5.3）
         *
         * 背景：5.5.2 实测 AdminDashboardView 分包已经 583 kB（ECharts 占绝大部分），
         * 带来两个问题：① Vite 每次构建都提示 ">500 kB chunk"；② 只要改动仪表盘视图的
         * 任何一行代码，这份 583 kB 的产物哈希就会变，浏览器缓存整块失效 —— 而 ECharts
         * 本身几周都不会动一次，它不该跟着业务代码一起变。
         *
         * ⚠️ 必须用**对象形式**（chunk 名 → 模块 id 列表）而不是函数形式 `(id) => {...}`：
         *    · 对象形式的 chunk 名是写死的（产出稳定的 `echarts-*.js`），
         *      verify:chunks 门禁可以直接按文件名断言；
         *    · 函数形式要自己写路径匹配，chunk 名与拆分边界随构建图漂移，
         *      而且很容易把 echarts 与业务模块塞进同一个 chunk（等于没拆）。
         *
         * 列表里只要写 ECharts 的四个入口即可：zrender / tslib 是它们的内部依赖，
         * 且全项目没有第二处引用，Rollup 会自动把它们一并归入本 chunk。
         *
         * 维护约定：以后再 import 别的 echarts 顶层入口（例如 'echarts/features'
         * 的 LabelLayout / UniversalTransition），**要同步加到这个数组里**，
         * 否则那部分代码会漏回业务 chunk。新增图表类型（LineChart 等）不用改这里 ——
         * 它们都在 'echarts/charts' 里。
         */
        manualChunks: {
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers']
        }
      }
    }
  },

  server: {
    port: 5173,
    // 端口被占用时直接报错，而不是悄悄换到 5174（避免论文里写的地址对不上）
    strictPort: true,
    open: false,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      '/static': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
