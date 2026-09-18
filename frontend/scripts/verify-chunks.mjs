#!/usr/bin/env node
/**
 * 构建产物分包门（verify:chunks，批次 5.5.3 新增）
 *
 * 为什么需要它：
 *   ECharts 是**按需引入**的（只 import echarts/core|charts|components|renderers），
 *   但如果 vite.config.js 的 manualChunks 被误删、或有人把某个 echarts 子模块
 *   直接 import 进业务代码，ECharts 会重新被并回业务 chunk。后果有三个，而且都很隐蔽：
 *     ① 主包/业务分包的体积悄悄涨几百 kB（首屏变慢，但功能完全正常，没人会注意）；
 *     ② 每次改业务代码都让这 500+ kB 的产物哈希失效（缓存全废，Vite 只在构建日志里提示一下）；
 *     ③ 构建日志里那句 ">500 kB chunk" 警告会被当成"老问题"忽略掉。
 *   这类问题单测与 typecheck 都抓不到（构建能过、页面能开），只能对**产物文件**做断言。
 *
 * 用法：
 *   npm run verify:chunks   # 只检查（要求 dist 已存在）
 *   npm run verify          # 先 build 再依次跑 verify:styles / verify:chunks（推荐）
 *
 * 维护约定（重要）：
 *   · 断言 1 的产物前缀（echarts-*.js）由 vite.config.js 的 manualChunks 键名决定，
 *     改键名要同步改这里；
 *   · 断言 2/3 的关键字是"echarts 代码的特征串"（zrender 是 ECharts 的渲染引擎，
 *     只要有一行 echarts 代码被打进某个 chunk，就一定会出现它）；
 *   · 以后新增基于其它大库（例如地图 GeoJSON、代码高亮）的懒加载页面时，
 *     照这个脚本再加一组断言即可。
 *
 * 依赖：只用 Node 内置模块（fs / path / url），不引第三方库。
 */

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** 以脚本所在位置推算项目根目录，保证从任何 cwd 调用都能跑 */
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const assetsDir = join(projectRoot, 'dist', 'assets')

/** ECharts 独立 chunk 的文件名前缀（= vite.config.js 里 manualChunks 的键名） */
const ECHARTS_CHUNK_PREFIX = 'echarts-'

/** ECharts 独立 chunk 的体积上限（kB，未压缩）：超出说明拆得不干净（例如把 echarts 全量引入了） */
const ECHARTS_CHUNK_MAX_KB = 900

/**
 * 「这段代码属于 ECharts」的判定标记（必须是**代码级**标记，见下方血泪说明）
 *
 * ⚠️ 绝对不要用裸串 'echarts' 当标记（本脚本第一版就是这么写的，结果误报两条）：
 *    拆出 echarts-<hash>.js 之后，**引用方的 chunk 里会出现这个 chunk 的文件名**
 *    （Vite 生成的 `__vite__mapDeps([...])` 依赖映射，用来做预加载），
 *    于是"入口主包"和"仪表盘分包"都会命中 'echarts' —— 恰恰把我们要验证的拆分判成失败。
 *    实测（5.5.3 构建产物）：裸串 'echarts' 在 echarts 分包命中 2 次、
 *    在入口与仪表盘分包各命中 1 次（全是文件名），完全无法区分。
 *
 *    下面三个都是**只有真正打进了 echarts 代码才会出现**的串：
 *      · zrender            —— ECharts 的渲染引擎，出现在内部路径/类名字符串里（实测命中 3 次）
 *      · getInstanceByDom   —— 顶层 API 名；导出对象的属性名不会被压缩器改名（实测命中 1 次）
 *      · [ECharts]          —— ECharts 自己打日志的前缀（实测命中 1 次）
 *    三者对入口 / 仪表盘 / 其它业务分包实测均为 0 命中。
 */
const ECHARTS_MARKERS = ['zrender', 'getInstanceByDom', '[ECharts]']

/** 不许出现 ECharts 代码的 chunk：入口主包（含 Vite 生成的若干 index-* 共享小块）+ 非仪表盘业务分包 */
const CLEAN_CHUNK_RULES = [
  { prefix: 'index-', guard: '入口主包与 index-* 共享块（首屏必需，任何页面都要下载）' },
  { prefix: 'AdminLayout-', guard: '管理端外壳（除仪表盘外所有管理页共用）' },
  { prefix: 'AdminProductAuditView-', guard: '商品审核页分包' },
  { prefix: 'AdminUserView-', guard: '用户管理页分包' },
  { prefix: 'AdminOrderView-', guard: '订单管理页分包' }
]

const DASHBOARD_CHUNK_PREFIX = 'AdminDashboardView-'
/** 仪表盘分包拆掉 ECharts 之后的体积上限（kB）：超过说明 ECharts 又漏回来了 */
const DASHBOARD_CHUNK_MAX_KB = 200

// ------------------------------------------------------------------ 读取产物
if (!existsSync(assetsDir)) {
  console.error('❌ 找不到构建产物目录：' + assetsDir)
  console.error('   请先执行 npm run build（或直接用 npm run verify，它会先 build 再检查）')
  process.exit(1)
}

const jsFiles = readdirSync(assetsDir)
  .filter((name) => name.endsWith('.js'))
  .map((name) => ({ name, file: join(assetsDir, name), size: statSync(join(assetsDir, name)).size }))
  .filter((item) => statSync(item.file).isFile())

if (jsFiles.length === 0) {
  console.error('❌ dist/assets 下没有任何 .js 文件，构建产物不完整')
  process.exit(1)
}

const kb = (bytes) => Math.round((bytes / 1024) * 100) / 100

/** 读文件内容（只读需要的少量 chunk，避免把整个产物读进内存） */
function readChunk(item) {
  return readFileSync(item.file, 'utf8')
}

/** 找出这个 chunk 里命中了哪些 ECharts 特征串（失败时用来定位） */
function markersIn(item) {
  const text = readChunk(item)
  return ECHARTS_MARKERS.filter((marker) => text.includes(marker))
}

const failures = []

// ------------------------------------------------------------------ 断言 1：存在独立的 echarts chunk
console.log(`检查构建产物分包：${jsFiles.length} 个 JS 文件（${assetsDir.replace(projectRoot, '.')}）`)
console.log('')

const echartsChunks = jsFiles.filter((item) => item.name.startsWith(ECHARTS_CHUNK_PREFIX))

if (echartsChunks.length === 0) {
  console.log(`❌ 未找到独立分包 ${ECHARTS_CHUNK_PREFIX}*.js`)
  failures.push({
    what: 'ECharts 独立分包不存在',
    why: 'vite.config.js 的 build.rollupOptions.output.manualChunks 可能被删除或改坏了',
    how: "确认存在 manualChunks: { echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'] }"
  })
} else {
  for (const item of echartsChunks) {
    const sizeKb = kb(item.size)
    if (sizeKb > ECHARTS_CHUNK_MAX_KB) {
      console.log(`❌ ${item.name}（${sizeKb} kB）超过上限 ${ECHARTS_CHUNK_MAX_KB} kB`)
      failures.push({
        what: `ECharts 分包体积异常（${sizeKb} kB）`,
        why: '可能被改成了全量引入（import * as echarts from "echarts"），按需引入后应远小于上限',
        how: '检查 src/utils/echarts.js 是否仍在用 echarts/core + echarts/charts + echarts/components + echarts/renderers'
      })
    } else {
      console.log(`✅ ${item.name}（${sizeKb} kB）`)
    }
  }
}

// ------------------------------------------------------------------ 断言 2：仪表盘分包已不含 ECharts
const dashboardChunk = jsFiles.find((item) => item.name.startsWith(DASHBOARD_CHUNK_PREFIX))
if (!dashboardChunk) {
  console.log(`❌ 未找到仪表盘分包 ${DASHBOARD_CHUNK_PREFIX}*.js`)
  failures.push({
    what: '仪表盘分包不存在',
    why: 'AdminDashboardView 的路由可能不再是懒加载',
    how: '检查 src/router/index.js 里 admin-dashboard 是否用 () => import(...) 懒加载'
  })
} else {
  const markers = markersIn(dashboardChunk)
  const sizeKb = kb(dashboardChunk.size)
  if (markers.length > 0) {
    console.log(`❌ ${dashboardChunk.name}（${sizeKb} kB）里仍含 ECharts 代码：命中 ${markers.join(', ')}`)
    failures.push({
      what: '仪表盘分包里残留 ECharts 代码',
      why: 'manualChunks 没把它拆走，或有人绕开 src/utils/echarts.js 直接 import 了 echarts 子模块',
      how: '全项目搜 import ... from "echarts"（除 src/utils/echarts.js 外的命中都要改）'
    })
  } else if (sizeKb > DASHBOARD_CHUNK_MAX_KB) {
    console.log(`❌ ${dashboardChunk.name}（${sizeKb} kB）超过上限 ${DASHBOARD_CHUNK_MAX_KB} kB`)
    failures.push({
      what: `仪表盘分包体积异常（${sizeKb} kB）`,
      why: '虽然没命中 echarts 特征串，但体积远超"纯视图代码"的量级，可能有别的重依赖被打进来',
      how: '构建时用 rollup-plugin-visualizer 或 --debug 检查该分包的模块构成'
    })
  } else {
    console.log(`✅ ${dashboardChunk.name}（${sizeKb} kB，不含 ECharts）`)
  }
}

// ------------------------------------------------------------------ 断言 3：主包等分包绝不能含 ECharts
for (const rule of CLEAN_CHUNK_RULES) {
  const targets = jsFiles.filter((item) => item.name.startsWith(rule.prefix))
  if (targets.length === 0) {
    console.log(`⚠️  未找到 ${rule.prefix}*.js（跳过：分包命名可能变了，需要同步本脚本的规则）`)
    continue
  }
  for (const item of targets) {
    const markers = markersIn(item)
    if (markers.length > 0) {
      console.log(`❌ ${item.name}（${rule.guard}）里含 ECharts 代码：命中 ${markers.join(', ')}`)
      failures.push({
        what: `${rule.prefix}*.js 里含 ECharts 代码`,
        why: 'ECharts 应当只被懒加载的仪表盘用到；出现在这里说明有页面/组件提前引用了它',
        how: `检查谁 import 了 src/utils/echarts.js 或 echarts 子模块（${rule.guard}不应该是其中之一）`
      })
    } else {
      console.log(`✅ ${item.name}（${rule.guard}）不含 ECharts`)
    }
  }
}

// ------------------------------------------------------------------ 结论
if (failures.length > 0) {
  console.log('')
  console.log(`❌ 分包检查未通过：${failures.length} 项`)
  console.log('')
  for (const item of failures) {
    console.log(`   问题：${item.what}`)
    console.log(`   原因：${item.why}`)
    console.log(`   排查：${item.how}`)
    console.log('')
  }
  process.exit(1)
}

console.log('')
console.log('✅ 分包检查通过：ECharts 已独立成 chunk，主包与其它业务分包都不含它')
process.exit(0)
