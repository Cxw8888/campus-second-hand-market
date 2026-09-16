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
