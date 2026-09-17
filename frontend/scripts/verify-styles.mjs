#!/usr/bin/env node
/**
 * 构建产物样式完整性门（verify:styles）
 *
 * 为什么需要它：
 *   上一批的「确认弹窗跑到屏幕左下角」Bug，根因是 ElMessage / ElMessageBox 的样式
 *   **压根没进构建产物** —— 这两个是函数式 API，业务里显式 `import { ... } from 'element-plus'`
 *   会绕过 unplugin-auto-import 的 ElementPlusResolver，样式不会被自动注入。
 *
 *   这类 Bug 有三个特点，导致常规手段都抓不到：
 *     ① JS 完全正常、组件能渲染、构建也不报错；
 *     ② 单测抓不到（jsdom 不加载 CSS，getComputedStyle 也拿不到布局）；
 *     ③ 看源码看不出来，只有翻产物 CSS 才能发现少了一整块。
 *   所以只能靠「对构建产物做断言」来防。
 *
 * 用法：
 *   npm run verify:styles   # 只检查（要求 dist 已存在）
 *   npm run verify          # 先 build 再检查（推荐，避免检查到过期产物）
 *
 * 维护约定（重要）：
 *   以后新增**函数式** Element Plus API（ElNotification / ElLoading / ElMessageBox 变体等），
 *   必须同步把它的样式关键字加进下面的 REQUIRED 列表，否则这道门对它是失效的。
 *   关键字一律带左大括号（如 `.el-message{`），避免误匹配到业务代码自己写的
 *   `.el-message-box__message` 这类后代选择器 —— 上一批就是被这个坑误导过一次。
 *
 * 依赖：只用 Node 内置模块（fs / path / url），不引第三方库。
 */

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** 以脚本所在位置推算项目根目录，保证从任何 cwd 调用都能跑 */
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const assetsDir = join(projectRoot, 'dist', 'assets')

/**
 * 必须出现在产物 CSS 里的关键字
 *
 * keyword  —— 精确子串（区分「库的规则」与「业务自己写的规则」）
 * guard    —— 这个关键字守护的是哪个 API / 样式入口，方便定位问题
 */
const REQUIRED = [
  {
    keyword: 'is-message-box',
    guard: 'ElMessageBox 居中链路的 wrapper（.is-message-box .el-overlay-message-box）'
  },
  {
    keyword: '.el-message-box{',
    guard: 'ElMessageBox 本体（display:inline-block / vertical-align:middle / max-width:420px）'
  },
  {
    keyword: '.el-message{',
    guard: 'ElMessage 轻提示（toast）'
  },
  {
    // 第五批新增：管理端商品审核表是**全项目第一次**用 <el-table>。
    // 模板里的组件由 unplugin-vue-components 自动补 import + 样式，理论上不会漏；
    // 但"理论上"正是上一批翻车的地方，所以这里给它加一道断言，
    // 避免出现"表格有数据却没有一点表格样式"这种只有打开页面才看得出来的问题。
    keyword: '.el-table{',
    guard: '管理端 el-table 表格（批次 5.1 首次引入）'
  },
  {
    keyword: '.el-table__header',
    guard: 'el-table 表头单元格样式（el-table-column 的样式入口）'
  },
  {
    // 5.2 新增：用户管理的「角色 / 状态」用 el-tag 呈现（这是本项目第一次用 el-tag）
    keyword: '.el-tag{',
    guard: '管理端 el-tag 标签（批次 5.2 首次引入：角色 / 用户状态）'
  },
  {
    // 5.2 新增：用户管理、订单管理两个列表都带分页
    keyword: '.el-pagination{',
    guard: 'el-pagination 分页器（管理端用户 / 订单列表）'
  },
  {
    // 5.3 新增：审计日志的时间范围筛选用 datetimerange
    keyword: '.el-date-editor',
    guard: 'el-date-editor 日期时间选择器（批次 5.3 首次引入：审计日志时间筛选）'
  },
  {
    // 5.3 新增：分类管理的新增/编辑表单弹窗 + 208 迁移引导弹窗
    keyword: '.el-dialog{',
    guard: 'el-dialog 对话框（批次 5.3 首次引入：分类新增/编辑 + 迁移引导）'
  },
  {
    // 5.3 新增：分类排序权重输入（整数、可步进）
    keyword: '.el-input-number',
    guard: 'el-input-number 数字输入框（批次 5.3 首次引入：分类排序权重）'
  },
  {
    // 5.4 新增断言：发布/编辑表单新增「分类已失效」提示条（ProductForm 第一次用 el-alert）。
    // 项目此前已有 el-alert（HomeView / 订单页等），但那些是别的批次的产物；
    // 这里补一条断言，保证本批新引入的提示条样式确实进了产物，而不是白底黑字。
    keyword: '.el-alert{',
    guard: 'el-alert 提示条（批次 5.4：ProductForm 分类失效提示）'
  }
]

// ------------------------------------------------------------------ 读取产物
if (!existsSync(assetsDir)) {
  console.error('❌ 找不到构建产物目录：' + assetsDir)
  console.error('   请先执行 npm run build（或直接用 npm run verify，它会先 build 再检查）')
  process.exit(1)
}

const cssFiles = readdirSync(assetsDir)
  .filter((name) => name.endsWith('.css'))
  .map((name) => join(assetsDir, name))
  .filter((file) => statSync(file).isFile())

if (cssFiles.length === 0) {
  console.error('❌ dist/assets 下没有任何 .css 文件，构建产物不完整')
  process.exit(1)
}

/** 把全部 CSS 产物拼成一份文本：关键字命中哪一块都算通过 */
const bundle = cssFiles.map((file) => readFileSync(file, 'utf8')).join('\n')

/** 统计精确子串出现次数（不区分大小写会误判，这里严格区分） */
function countOf(text, keyword) {
  let count = 0
  let from = 0
  for (;;) {
    const at = text.indexOf(keyword, from)
    if (at === -1) break
    count += 1
    from = at + keyword.length
  }
  return count
}

/** 找出这个关键字具体出现在哪些文件里（失败时用来定位） */
function filesContaining(keyword) {
  return cssFiles
    .filter((file) => readFileSync(file, 'utf8').includes(keyword))
    .map((file) => file.slice(projectRoot.length + 1).replace(/\\/g, '/'))
}

// ------------------------------------------------------------------ 断言
console.log(`检查产物样式：${cssFiles.length} 个 CSS 文件（${assetsDir.replace(projectRoot, '.')}）`)
console.log('')

const missing = []
for (const item of REQUIRED) {
  const count = countOf(bundle, item.keyword)
  if (count > 0) {
    console.log(`${item.keyword} ✅ 命中 ${count} 次`)
  } else {
    console.log(`${item.keyword} ❌ 命中 0 次`)
    missing.push(item)
  }
}

if (missing.length > 0) {
  console.log('')
  console.log(`❌ 样式完整性检查未通过：${missing.length} 个关键字在产物中缺失`)
  console.log('')
  for (const item of missing) {
    console.log(`   缺失：${item.keyword}`)
    console.log(`   守护：${item.guard}`)
  }
  console.log('')
  console.log('排查方向：')
  console.log('   1) 该 API 是否为「函数式」调用（ElMessage / ElMessageBox / ElNotification / ElLoading）？')
  console.log('      是的话它多半是显式 import 的，必须去 src/main.js 顶部补对应的样式入口：')
  console.log("         import 'element-plus/es/components/<name>/style/css'")
  console.log('   2) 若已补过，确认 dist 不是过期产物（用 npm run verify 会先 build）')
  console.log(`   3) 命中的文件清单：${filesContaining(REQUIRED[0].keyword).join(', ') || '（无）'}`)
  process.exit(1)
}

console.log('')
console.log('✅ 样式完整性检查通过：函数式 API 的样式都已进入构建产物')
process.exit(0)
