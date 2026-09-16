# 校园二手交易平台 · 前端

Vue 3 + Vite + Element Plus 构建的校园二手交易前端。第一批交付：**登录/注册、首页商品列表、商品详情、404**。

---

## 一、快速开始

```bash
# 1) 先启动后端（必须）
#    Spring Boot 监听 http://127.0.0.1:8080

# 2) 前端
cd frontend
npm install
npm run dev
# 打开 http://localhost:5173
```

> **后端必须先起来。** 前端所有接口都走 Vite 代理到 `127.0.0.1:8080`。
> 若后端没起，首页会自动降级成本地演示数据（见第五节），不会白屏，但登录必然失败。

常用命令：

| 命令 | 作用 |
| --- | --- |
| `npm run dev` | 开发服务器（5173，端口被占用会直接报错而不是静默换端口） |
| `npm run build` | 生产构建（产物在 `dist/`） |
| `npm run preview` | 本地预览构建产物 |

---

## 二、目录结构

```
frontend/
├── index.html                     # 单页入口（内联 SVG favicon，零静态资源依赖）
├── vite.config.js                 # 端口 / 代理 / Element Plus 按需自动引入 / SCSS 变量注入
├── vitest.config.js               # 测试配置（jsdom + 内联 element-plus + 关闭 CSS 编译）
├── jsconfig.json                  # 让 IDE 认识 @ → src 别名（纯 JS 项目不做类型检查）
└── src/
    ├── main.js                    # 应用入口：装上 pinia → router → 挂载
    ├── App.vue                    # 应用外壳：导航栏显示策略 + 路由过渡
    ├── api/                       # 接口层（按后端模块分文件）
    │   ├── auth.js                #   登录 / 注册 / 退出 / 邮箱验证码
    │   ├── product.js             #   列表 / 详情 / 发布 / 编辑 / 下架 / 删除 / 我的商品
    │   ├── order.js               #   防重Token / 下单 / 列表 / 详情 / 支付 / 发货 / 退款
    │   ├── favorite.js            #   收藏 / 取消收藏 / 是否已收藏 / 我的收藏列表
    │   ├── user.js                #   个人资料 / 改密码 / 换绑邮箱
    │   ├── notification.js        #   站内信列表 / 未读数 / 标记已读
    │   ├── upload.js              #   图片上传（multipart，字段名 file）
    │   └── mock.js                #   本地兜底数据（仅接口报错时使用）
    ├── assets/styles/
    │   ├── variables.scss         # 设计变量 + mixin（被 Vite 自动注入每个 SCSS 文件）
    │   └── index.scss             # 全局样式 + Element Plus 主题变量覆盖
    ├── components/
    │   ├── AppHeader.vue          # 顶部导航（Logo / 搜索 / 发布 / 用户下拉）
    │   ├── AuthBrandPanel.vue     # 登录页左侧品牌插画区（手写 SVG）
    │   ├── BrandLogo.vue          # 品牌标识（内联 SVG，支持深/浅两种配色）
    │   ├── CategorySidebar.vue    # 左侧分类侧边栏
    │   ├── ConditionTag.vue       # 成色标签（绿/蓝/橙/灰四色语义）
    │   ├── EmptyState.vue         # 空状态
    │   ├── ProductImage.vue       # 商品图（带「暂无图片」占位兜底）
    │   ├── ProductCard.vue        # 首页商品卡片
    │   ├── OrderCard.vue          # 订单列表卡片
    │   ├── OrderStatusTag.vue     # 订单状态标签（8 种状态配色）
    │   ├── OrderTimeline.vue      # 订单进度时间线（按面交/取消/冻结动态生成节点）
    │   ├── PayCountdown.vue       # 待支付倒计时（15 分钟，归零触发回调）
    │   ├── ProductForm.vue        # 商品表单（发布页与编辑页共用）
    │   ├── ImageUploader.vue      # 多图上传（canvas 压缩 + 进度 + 预览 + 删除）
    │   └── MyProductCard.vue      # 我的商品列表项（按状态给操作按钮）
    ├── router/index.js            # 路由表 + 登录拦截守卫 + 标题同步
    ├── stores/
    │   ├── index.js               # pinia 实例 + 持久化插件
    │   └── user.js                # token / userInfo / 登录登出
    ├── utils/
    │   ├── request.js             # axios 封装：请求注入 Bearer、响应脱壳、401 跳登录
    │   ├── auth.js                # token 落盘（唯一出口，避免循环依赖）
    │   ├── constants.js           # 分类/成色/交易方式/商品状态/订单状态字典 + 分页常量
    │   ├── format.js              # 价格/时间/图片地址/空值处理
    │   └── image.js               # 图片压缩（canvas，最大边长 1920 + 质量 0.8）
    └── views/
        ├── AuthView.vue                     # 登录 + 注册（/login、/register 共用）
        ├── HomeView.vue                     # 首页商品列表
        ├── ProductDetailView.vue            # 商品详情
        ├── OrderCreateView.vue              # 下单确认（交易方式动态字段）
        ├── OrderSuccessView.vue             # 下单成功 + 倒计时 + 支付
        ├── OrderListView.vue                # 我的订单（买家/卖家 Tab）
        ├── OrderDetailView.vue              # 订单详情（状态×角色决定操作）
        ├── ProductPublishView.vue           # 发布商品
        ├── ProductPublishSuccessView.vue    # 发布成功
        ├── ProductMyView.vue                # 我的商品（5 状态 Tab）
        ├── ProductEditView.vue              # 编辑商品（含归属校验）
        ├── UserProfileView.vue              # 个人中心
        ├── ForbiddenView.vue                # 403
        ├── PlaceholderView.vue              # 第四批页面的占位
        ├── NotFoundView.vue                 # 404
        └── __tests__/                       # vitest 用例（详见第七节）
            ├── OrderCreateView.spec.js
            └── ProductPublishView.spec.js
```

---

## 三、后端对接要点（踩过的坑，都已在代码里处理）

### 1. 所有 Long 都被序列化成字符串

后端 `JacksonConfig` 会把 **所有 `Long` 转成 JSON 字符串**，所以
`id`、`total`、`pages`、`current`、`size`、`userId` 拿到的都是 `"25"` 而不是 `25`。

前端对应处理：
- 分页比较一律 `Number(total)` 之后再判（见 `HomeView.fetchList`）
- 价格是 `BigDecimal`，**不受影响**仍是数字，用 `formatPrice()` 统一补两位小数
- 路由参数本来就是字符串，天然兼容

### 2. 业务失败也是 HTTP 200

只有「未登录 / Token 失效」才返回真实 HTTP 401。所以：

- `utils/request.js` 的响应拦截器按 `body.code` 判断成败
- 成功时**脱壳**返回 `body.data`，业务代码里不用写 `.data.data`
- 失败时统一 `ElMessage` 提示 + reject 一个 `BizError`；需要自己处理的调用方传 `{ silent: true }`
- HTTP 401 → 清 token + 清 Pinia + 跳 `/login`（带 `redirect` 回跳）

### 3. 商品图片目前全部会 404

后端 `uploads/` 目录是空的，seed 数据里的 `/static/uploads/demo*.jpg` 并不存在。
所以：

- `vite.config.js` 里把 `/static` 也代理到 8080（否则相对路径会打到 5173）
- `ProductImage.vue` 用 `@error` 统一降级成「浅绿渐变 + 暂无图片」占位图
- 列表页因此**不会有碎图**，观感上像是刻意设计的空态

想换成真图，把图片放到后端 `uploads/` 目录即可，前端无需改动。

### 4. 邮箱验证码在降级模式下会直接返回

`app.email.skip=true` 时 `GET /auth/email-code` 会把验证码放在 `data.code` 里返回。
注册页拿到后会：

1. 用**绿色提示条**醒目显示（答辩演示用）
2. 自动回填到验证码输入框（免手输）
3. 点击提示条可复制

若 `skip=false`（真实发信），则不显示提示条，只提示「已发送至邮箱」。

---

## 四、设计规范

| 用途 | 色值 | 语义 |
| --- | --- | --- |
| 主色 | `#10B981` | 品牌色：Logo、导航高亮、次级按钮、成功态 |
| 强调色 | `#F59E0B` | 交易色：价格、立即购买、库存告急 |
| 背景 | `#F9FAFB` | 页面底色 |
| 主文字 | `#1F2937` | 标题与正文 |
| 次要文字 | `#6B7280` | 辅助说明 |

**成色标签配色**：全新=绿 / 几乎全新=蓝 / 轻微使用=橙 / 明显使用=灰

**渐变的克制使用**（只有三处）：
1. 登录页顶部细装饰条 + 左侧品牌区背景（品牌绿三段渐变）
2. 「立即购买」按钮（橙 → 金）
3. 导航栏头像、404 大号数字

**卡片**：白底 + 12px 圆角 + `0 2px 12px rgba(0,0,0,.08)` 柔和阴影，hover 上浮 4px。
统一由 `variables.scss` 里的 `cm-card` / `cm-hover-lift` 两个 mixin 提供，避免各页面各写一套。

Element Plus 的主题不是用 SCSS 编译改的，而是在 `index.scss` 里覆盖 `--el-color-primary` 等
**CSS 变量**（含 light-3/5/7/8/9 各档，缺了这些 hover 态会退回默认蓝）。这样比 SCSS 主题链更稳、更少坑。

---

## 五、数据策略：mock 什么时候才用

首页商品列表的三种状态是**互斥**的，不要混：

| 情况 | 表现 |
| --- | --- |
| 接口成功且 `total > 0` | 渲染真实数据 |
| 接口成功但 `total === 0` | 显示「暂无商品，快去发布第一件吧」空状态 |
| **接口报错** | 才用本地 mock（8 条）兜底 + `console.warn` + 顶部黄色提示条（带「重试」按钮） |

关键点：**`total === 0` 时绝不用 mock 兜底**，否则会把「后端确实没有数据」这个事实盖掉，
排查问题时会被误导。

---

## 六、已完成范围与后续

**第一批：基础浏览链路**

- `/login`、`/register`：品牌插画区 + Tab 切换 + 完整表单校验 + 60 秒倒计时 + 降级验证码可视化
- `/`：分类侧边栏、排序/价格区间筛选、3 列卡片网格、分页、空状态、mock 兜底
- `/product/:id`：图集轮播、商品信息、卖家卡片、收藏与立即购买
- `/:pathMatch(.*)*`：404
- 全站：登录拦截守卫、401 自动跳登录、退出登录

**第二批：交易链路**

- `/order/create`：商品摘要、**交易方式动态字段**（面交地点 / 收货地址 / 二选一）、数量受库存限制、
  二次确认弹窗、防重 Token 流程（先取 Token 再下单）
- `/order/success/:orderId`：大绿勾 + 15 分钟倒计时 + 立即支付
- `/order/list`：买家 / 卖家 Tab、状态标签、分页 10 条
- `/order/detail/:orderId`：状态时间线 + 按「状态 × 角色」显示操作按钮（支付/取消/发货/收货/面交完成/退款处理）

**第三批：卖家链路 + 个人中心**

- `/product/publish`：完整发布表单 + **canvas 图片压缩上传**（最大边长 1920、质量 0.8、最多 9 张）
- `/product/publish-success/:productId`：发布成功、等待审核
- `/product/my`：5 个状态 Tab（带数量角标）、编辑 / 下架 / 重新上架 / 删除
- `/product/edit/:id`：与发布页共用表单，回填数据、归属校验（非本人 → 403）、按状态规则给不同提示
- `/user/profile`：快捷入口（收藏 / 消息 / 订单，带角标）、资料与头像、修改密码、换绑邮箱
- `/403`：无权限页（与 404 区分开）

**占位（第四批）**

- `/favorite/list` 我的收藏列表、`/notification/list` 消息中心
  （后端接口已就绪，收藏写入与未读数角标都已经接通，只差列表页）

---

## 七、前端测试

### 怎么跑

```bash
cd frontend
npm install         # 首次会装上 vitest / @vue/test-utils / jsdom
npm test            # 跑一遍全部用例（等价于 vitest run）
npm run test:watch  # 开发时监听改动自动重跑
npm run verify      # 先 build，再检查构建产物的样式完整性（见下）
npm run verify:styles  # 只跑样式检查（要求 dist 已存在）
```

### 测什么

| 文件 | 覆盖内容 |
| --- | --- |
| `src/views/__tests__/OrderCreateView.spec.js` | 下单页防连点：连点 5 次只弹 1 个确认框；确认后只发 1 次请求；202 时提示并刷新防重 Token |
| `src/views/__tests__/ProductPublishView.spec.js` | 发布页防连点：连点 5 次只发 1 次请求；校验失败也解锁；面交地点随交易方式联动校验 |

当前共 **6 个用例**，`npm test` 全绿。

### 为什么专门测「连点」

这不是凑数的用例。早期下单页有一个真实 Bug：`submitting` 在「确认弹窗之后」才置 true，
于是从点击到弹窗出现这段时间按钮仍可点，快速连点会**堆叠出多个确认对话框**。

更值得记住的是修复过程：第一版把 `submitting = true` 提到弹窗之前，但后面还留着
`await formRef.validate()` —— 因为 `await` 会让出执行权，5 次点击仍会在任何一次置位之前
全部穿过入口守卫，实测**依然是 5 个弹窗**。是这条测试先红了才暴露出来的。

结论（也是这两条用例真正守住的东西）：**上锁必须在任何 `await` 之前同步完成**，
解锁放在 `finally`。谁要是把上锁时机挪到 `await` 后面，`npm test` 立刻会红。

### 配置要点

`vitest.config.js` 有三处非默认设置，都是实测踩出来的：

1. `environment: 'jsdom'` —— 组件要真的挂到 DOM 上才能测点击交互。
2. `server.deps.inline: [/element-plus/]` —— vitest 默认把 node_modules 外部化交给 Node 原生 ESM，
   而 Element Plus 的按需引入会 `import '.../style/css'`（本质是 `.css`），
   Node 原生 ESM 不认 `.css`，会报 `ERR_UNKNOWN_FILE_EXTENSION`。内联后交给 Vite 处理。
3. `css: false` —— 测试不需要编译样式，关掉能明显加快启动。

### 构建产物样式门（`npm run verify`）

`scripts/verify-styles.mjs` 会读取 `dist/assets/*.css`，断言几个函数式 API 的样式关键字
各至少命中 1 次，缺任何一个就 `exit 1`：

```
is-message-box        ✅ 命中 2 次     # ElMessageBox 居中链路的 wrapper
.el-message-box{      ✅ 命中 1 次     # 弹窗本体（display:inline-block / vertical-align:middle）
.el-message{          ✅ 命中 1 次     # ElMessage 轻提示
```

**为什么单测防不住这类问题**：jsdom 不加载 CSS、也拿不到布局，所以「样式压根没进产物」
在单测里完全看不见；而且 JS 正常、组件能渲染、构建也不报错。只有对构建产物做断言才抓得到。

**维护约定（重要）**：以后新增**函数式** Element Plus API（`ElNotification`、`ElLoading` 等），
必须同步把它的样式关键字加进 `scripts/verify-styles.mjs` 的 `REQUIRED` 列表 ——
否则这道门对新 API 是失效的。原因见 `src/main.js` 顶部注释：
显式 `import { ElXxx } from 'element-plus'` 会绕过 auto-import 的样式注入。

关键字一律**带左大括号**（`.el-message-box{` 而不是 `.el-message-box`），
避免误匹配到业务代码自己写的 `.el-message-box__message` 这类后代选择器 —— 这个坑踩过一次。

---

## 八、常见问题

**Q：页面能打开但商品列表是本地演示数据？**
A：后端没启动。确认 `http://127.0.0.1:8080/actuator/health` 能访问，然后点列表上方的「重试」。

**Q：登录提示「无法连接后端服务」？**
A：同上。前端不会伪造登录态。

**Q：端口 5173 被占用启动失败？**
A：`vite.config.js` 里设了 `strictPort: true`，故意让它报错。关掉占用的进程，或改端口后同步改后端 CORS 配置。

**Q：`npm install` 很慢或超时？**
A：默认 registry 已是国内镜像 `registry.npmmirror.com`。若你的环境不同，可临时加 `--registry=https://registry.npmmirror.com`。

**Q：`npm test` 报 `ERR_UNKNOWN_FILE_EXTENSION: .css`？**
A：说明 `vitest.config.js` 里的 `server.deps.inline` 配置丢了或被覆盖了，Element Plus 的样式导入没能交给 Vite 处理。

**Q：确认弹窗（ElMessageBox）跑到屏幕左下角、标题贴左边缘、按钮贴底？**
A：不是 CSS 被覆盖，而是 `el-message-box` / `el-message` 的样式**压根没进构建产物**。
根因：这两个是「函数式」API，业务里是显式 `import { ElMessage, ElMessageBox } from 'element-plus'`，
而 unplugin-auto-import 的 ElementPlusResolver **只处理未声明就使用的标识符**，显式 import 会绕过它，
样式不会被自动注入。修复见 `src/main.js` 顶部的显式样式引入（那里有完整说明与维护提醒）。
自查方法：`npm run build` 后在 `dist/assets/*.css` 里搜 `is-message-box`，搜不到就是这个问题又回来了。

