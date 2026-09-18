/**
 * ECharts 按需引入（批次 5.5.1）
 *
 * ⚠️ **严禁**写 `import * as echarts from 'echarts'`：
 *    全量包 1MB+（未压缩），会让首屏和构建产物一起变胖，而且我们只用了饼图 + 条形图两种图。
 *    这里按官方推荐的「core + 按需注册」写法：先 import 具体图表/组件，再 echarts.use([...])。
 *    漏注册的组件在运行时会抛 `Component xxx not exists.`（构建期不报错），
 *    所以新增图表类型时必须回来补这里的清单（文件末尾有清单说明）。
 *
 * ⚠️ 所有用到本模块的地方都从**这里** import 默认导出，不要再直接 import 'echarts/*'：
 *    一旦某处绕开本文件单独引入，就会出现"两份 echarts 实例"，图表互相拿不到
 *    （表现为 getInstanceByDom 返回 undefined、resize 失效）。
 *
 * 注册清单（与本批实际用法一一对应）：
 *   · 图表：PieChart（订单状态分布）、BarChart（商品分类分布）
 *   · 组件：TitleComponent、TooltipComponent、LegendComponent、GridComponent
 *     （GridComponent 同时带来直角坐标系的两根轴，条形图靠它；标题其实由页面的卡片承担，
 *       但保留注册，5.5.2 的趋势图直接可用）
 *   · 渲染器：CanvasRenderer（默认，无需额外依赖）
 *   · features：**未引入**。LabelLayout 只在显式使用 labelLayout 配置时才需要；
 *       饼图的 avoidLabelOverlap 由 PieChart 内部自带（echarts/lib/chart/pie/labelLayout.js）。
 */
import * as echarts from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  // 图表
  PieChart,
  BarChart,
  // 组件
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  // 渲染器
  CanvasRenderer
])

/**
 * 语义色调 → 图表色值。
 *
 * 为什么要在 JS 里再存一份色值：SCSS 变量（$cm-primary 等）在**运行时**是拿不到的，
 * ECharts 只接受真实色值。所以这里把设计变量镜像成 hex，键与 constants.js 里
 * 字典的 tone 语义同名（green/blue/orange/gray/…），保证「同一个语义在任何页面都是同一个颜色」。
 * 改动 $cm-* 时请同步这里（与 TONE_TO_TAG_TYPE 的维护要求相同）。
 */
export const CHART_TONE_COLORS = {
  green: '#10b981', // $cm-primary —— 正向（在售 / 已完成）
  blue: '#3b82f6', // $cm-blue —— 进行中（已支付）
  orange: '#f59e0b', // $cm-accent —— 需要注意（待支付 / 已售罄）
  darkorange: '#d97706', // $cm-accent-dark —— 严重（退款被拒）
  yellow: '#eab308', // 黄 —— 退款申请中
  purple: '#8b5cf6', // 紫 —— 已发货
  gray: '#6b7280', // $cm-gray-tag —— 终止（已取消）
  frozen: '#4b5563', // 深灰 —— 已冻结（带锁语义）
  // 兜底：未知 tone 用中性灰，绝不因为多了一个 tone 就渲染成黑色
  unknown: '#9ca3af'
}

/** 语义色调 → 色值（未知 tone 兜底为中性灰，不抛错） */
export function toneColor(tone) {
  return CHART_TONE_COLORS[tone] ?? CHART_TONE_COLORS.unknown
}

export default echarts
