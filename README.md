# 校园二手交易平台（V32 对齐）

Java 21 + Spring Boot 3 + MyBatis-Plus + MySQL 8.0 + Redis + Flyway + ShedLock 的后端，
Vue 3 + Vite + Element Plus 的前端。统一包名 `com.campus.market`，接口统一前缀 `/api/v1/`。

业务闭环：注册 / 邮箱验证码 / 登录 → 发布商品 → 管理员审核 → 搜索 / 收藏 → 下单 → 支付 / 面交 →
发货 / 收货 → 退款 / 申诉 → 消息通知 → 管理端治理与数据统计。

| 端 | 技术栈 |
| :--- | :--- |
| 后端 | Java 21、Spring Boot 3.2.5、MyBatis-Plus 3.5.5、MySQL 8.0、Redis、Flyway 10、ShedLock 5.10、jjwt 0.12.5、knife4j 4.4 + springdoc 2.3 |
| 前端 | Vue 3（`<script setup>`）、Pinia、Vue Router 4、Element Plus 2.14、ECharts 6.1、Vite 5.4、Vitest + @vue/test-utils |

> 版本号 `V32` 指 `PROJECT_CONTEXT.md` 头部变更记录中的当前版本（V32 = 上传加固 + 定时任务加固 + 前端缩略图）。
> 各文档的版本对齐口径见「六、文档索引与同步约定」。

---

## 一、交付内容

| 交付物 | 路径 |
| :--- | :--- |
| 接口清单（路径 / 方法 / 请求 / 响应 / 错误码 / 路径语义） | `docs/API_INTERFACE_SPEC.md` |
| 项目上下文（分章设计说明、变更记录、错误码表） | `PROJECT_CONTEXT.md` |
| 自审报告（安全问题台账 + 修复记录） | `docs/自审报告-2026-09-19.md` |
| Flyway 建表脚本（8 张表 + FULLTEXT 索引迁移） | `src/main/resources/db/migration/V1__init.sql`、`V2__add_fulltext_index.sql` |
| Maven 依赖 | `pom.xml` |
| 应用配置（dev / prod + 环境变量） | `src/main/resources/application.yml`、`application-dev.yml`、`application-prod.yml` |
| Entity（7 个：user / category / product / order / favorite / notification / auditLog） | `src/main/java/com/campus/market/entity/` |
| Mapper（含 CAS 扣减 / 回补 + 全部状态机 SQL） | `src/main/java/com/campus/market/mapper/` |
| 全局异常 + 统一响应 + 错误码 | `common/exception/`、`common/result/`、`common/enums/` |
| 拦截器与过滤器（三类路径语义、Token version、安全响应头、requestId） | `security/`、`common/filter/`、`config/WebMvcConfig.java` |
| Controller（12 个、61 个端点） | `src/main/java/com/campus/market/controller/` |
| Service 接口 + 实现（含库存回补统一入口 `StockService`） | `src/main/java/com/campus/market/service/` |
| 配置类（MyBatis-Plus / Jackson / CORS / 线程池 / ShedLock / 安全 / 静态资源） | `src/main/java/com/campus/market/config/` |
| 后端单元测试（42 个测试类、222 个用例） | `src/test/java/` |
| 前端工程（17 个视图、27 条路由、9 个 api 模块、25 个组件） | `frontend/` |
| 前端单元测试（18 个测试文件、187 个用例） | `frontend/src/**/__tests__/` |
| 前端构建门禁（样式隔离 / 分包断言） | `frontend/scripts/verify-styles.mjs`、`verify-chunks.mjs` |
| 手工联调脚本（HTTP 用例集） | `api-tests.http` |
| 每批交付模板 | `BATCH_TEMPLATE.md` |

---

## 二、构建与运行

### 2.1 后端

```bash
# 1. 建库（Flyway 启动时自动执行 V1 / V2，无需手工建表）
mysql -uroot -p -e "CREATE DATABASE campus_market DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 2. 编译
mvn -q clean compile

# 3. 启动（敏感配置走环境变量，dev 均有默认值）
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

本机环境说明：当前开发机 PATH 上没有 Maven，`mvn` 命令仅作为标准入口给出。
实际验证方式是直接用 JDK 21（`C:\Users\chen\.jdks\ms-21.0.12`）的 `javac` + 从
`C:\Users\chen\.m2\repository` 组装的 classpath 编译，并用 JUnit Platform Launcher 跑全量用例
（脚本在 `target/batch-6.0.2/` 下，属构建产物、不入库）。

启动后：

| 地址 | 说明 |
| :--- | :--- |
| `http://127.0.0.1:8080/actuator/health` | 健康检查（完全公开） |
| `http://127.0.0.1:8080/doc.html` | knife4j 接口文档（**仅非 prod**，prod 下由 `PublicPathResolver` 摘除 + `ApiDocGuardInterceptor` 拦成 `code=100`） |
| `http://127.0.0.1:8080/swagger-ui.html` | Swagger UI（**仅非 prod**，同上） |

### 2.2 前端

```bash
cd frontend
npm install          # 依赖已在 package.json / lock 中声明，无需新增
npm run dev          # http://127.0.0.1:5173（strictPort，端口被占用直接报错）
npm test             # vitest run（18 文件 / 187 用例）
npm run verify       # build + verify:styles + verify:chunks
```

Vite 把 `/api` 与 `/static` 都代理到 `http://127.0.0.1:8080`（见 `frontend/vite.config.js`）：
浏览器只与 5173 同源通信，因此**联调走代理、不依赖 CORS**；
`/static` 也必须代理，否则 `/static/uploads/...` 会被解析到 5173 而 404，商品列表整片空白。

### 2.3 关键环境变量

| 变量 | 说明 | 默认（dev） |
| :--- | :--- | :--- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | 数据库 | 127.0.0.1:3306/campus_market, campus_user/123456（prod 无默认值） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | Redis | 127.0.0.1:6379、空密码、db 0（prod 强制 `REDIS_PASSWORD`） |
| `JWT_SECRET` | **必须 >= 32 字节** | dev 占位值；**prod 无默认值**，`JwtUtils.init()` 启动断言会拦下未注入与"用了 dev 占位值"两种情况 |
| `JWT_EXPIRE_HOURS` | Token 有效期 | 2 |
| `CORS_ALLOWED_ORIGINS` | 前端域名，**严禁 `*`** | http://localhost:5173,http://127.0.0.1:5173（prod 无默认值） |
| `EMAIL_SKIP` | 验证码降级开关 | `application.yml` 里默认 **false**；但 `application-dev.yml` 内写死 `app.email.skip: true`（答辩降级），dev 下以 profile 值为准 |
| `CAMPUS_EMAIL_SUFFIXES` | 校园邮箱后缀允许列表 | @stu.edu.cn,@campus.edu.cn |
| `STORAGE_TYPE` | `local`(默认) / `minio` | local |
| `STORAGE_LOCAL_PATH` / `STORAGE_URL_PREFIX` | 上传根目录 / 访问前缀 | ./uploads、/static/uploads |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET` | 对象存储（`STORAGE_TYPE=minio` 时才使用） | 空 / campus-market |
| `PAY_CALLBACK_SECRET` | 支付回调 HMAC 密钥（>= 32 字节） | **空**：此时回调一律拒绝（fail-closed），绝不"没签名也放行" |
| `SNOWFLAKE_WORKER_ID` / `SNOWFLAKE_DATACENTER_ID` | **order_no 雪花算法必须配置，多实例唯一** | 1 / 1 |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP（`app.email.skip=false` 时才使用） | smtp.example.com / 465 / 空 |
| `SERVER_PORT` | 服务端口 | 8080 |

以下配置**没有**环境变量绑定，需要直接写在 yml 里（`application.yml` 已给出注释与推荐值）：

| 配置项 | 说明 | 默认 |
| :--- | :--- | :--- |
| `app.security.trusted-proxies` | 可信反向代理列表（IP 或 CIDR）。**只有直连对端命中该列表时才采信 `X-Forwarded-For` / `X-Real-IP`** | 空列表 = 不采信任何转发头（最安全） |
| `app.storage.max-files-per-user` | 单用户文件数配额 | 100 |
| `app.storage.max-total-size-mb-per-user` | 单用户总容量配额（MB） | 50 |
| `search.cache.*` / `search.circuit-breaker.*` | 搜索缓存（60s + 抖动）与 500ms 超时熔断开关 | 均开启 |
| `app.task.*` | 超时取消 / 自动确认 / 退款申诉恢复的窗口与 ShedLock 占用时长 | 15 分钟 / 7 天 / 3 天 |

prod 还多两道启动断言（fail-fast，见 `EmailCodeServiceImpl#assertSkipNotUsedInProd` 与 `PayCallbackSignService`）：
`app.email.skip=true` 或 `PAY_CALLBACK_SECRET` 未注入都会**直接拒绝启动**，而不是打个 warn 放过去。

---

## 三、三个核心技术约束的实现位置

### 1. CAS 扣减库存与幂等回补

- **扣减**：`ProductMapper#deductStock`
  ```sql
  UPDATE tb_product SET status = CASE WHEN stock - #{quantity} = 0 THEN 2 ELSE status END,
                        stock  = stock - #{quantity}
   WHERE id = #{id} AND stock >= #{quantity} AND status = 1 AND is_deleted = 0
  ```
  `status` 必须写在 `stock` 之前：MySQL 逐列更新，写在前面的表达式读到的是旧值；
  反过来会把"即将售罄"误判成"已售罄"（stock=3 连续两次 qty=1 后库存还剩 1 却被置为 2）。
- **回补（唯一入口）**：`StockService#restoreOnce(orderId, productId, quantity)` → `ProductMapper#restoreStock` + `#relistIfSoldOut`
  ```sql
  UPDATE tb_product SET stock = stock + #{quantity} WHERE id = #{id} AND is_deleted = 0
  -- 售罄商品仍有库存 → 单独一步重新上架
  UPDATE tb_product SET status = 1 WHERE id = #{id} AND status = 2 AND stock > 0 AND is_deleted = 0
  ```
  回补前先抢占幂等凭证 `order:restored:{orderId}`（SETNX，TTL 30 天）：抢不到即视为该订单已补过。
  订单状态机的 `WHERE status = 0` 只能防"同一条流转重复执行"，挡不住**两条不同路径先后对同一订单回补**
  （自审报告 B1）；凭证与事务的边界处理（未命中 / 抛异常 / 事务回滚都归还凭证，Redis 不可用降级放行）见 `StockService` 类注释。
- 调用点（**全部与订单状态更新同一事务，且仅影响行数 > 0 时执行**）：
  1. 买家主动取消 0→4：`OrderServiceImpl#cancel`
  2. 超时自动取消 0→4：`OrderTaskProcessor`（由 `ScheduledTasks` 调起，每单独立事务）
  3. 管理端解冻转取消 5→4：`AdminServiceImpl#unfreezeOrder`
  4. 卖家同意退款 6→4：`OrderServiceImpl#agreeRefund`
  5. 管理员强制退款 6/7→4：`AdminServiceImpl#forceRefund`
- **批次 6.0.3 · B1 起，"封禁冻结 0/1/2/6→5"不再回补库存**：冻结只是暂停交易，真正终止交易的是
  5→4；封禁时就回补会让后续 5→4 再补一次（库存 1 的商品被刷成 2，反复封禁/解冻可无限刷）。
- MySQL 行锁即保证并发安全，**未引入 Redisson**。

### 2. 退款状态机

| 流转 | SQL 位置 | 附加动作 |
| :--- | :--- | :--- |
| 1/2→6 买家申请 | `OrderMapper#applyRefund` | 通知卖家 |
| 6→4 卖家同意 | `OrderMapper#agreeRefund` | **+库存回补** |
| 6→7 卖家拒绝 | `OrderMapper#rejectRefund` | 进入 3 天申诉期 |
| 7→1/2 超时自动恢复 | `OrderMapper#recoverFromRefundRejected` + `ScheduledTasks` | `CASE WHEN ship_time IS NULL THEN 1 ELSE 2 END` |
| 6/7→4 管理员强制 | `OrderMapper#forceRefund` | +库存回补 +审计日志 |
| 7 状态买家再操作 | `OrderServiceImpl#pay/#applyRefund` | 抛 `ErrorCode.REFUND_REJECTED_WAIT_APPEAL`(206) |

### 3. 拦截器三类路径语义

`security/AuthInterceptor.java` + `common/constant/PathConstants.java`（prod 的有效公开集合由
`security/PublicPathResolver` 计算，会摘除接口文档相关路径）：

| 类别 | 路径 | 行为 |
| :--- | :--- | :--- |
| ① 完全公开 | `/actuator/health`、`/actuator/prometheus`、`/actuator/info`、`/swagger-ui/**`、`/v3/api-docs/**`、`/doc.html`、`/webjars/**`、`/favicon.ico`、`/error`、`/static/**` | 直接 `return true`，**不解析 Token** |
| ② 可选认证 | `/api/v1/product/detail/**`、`/api/v1/product/list`、`/api/v1/category/list`、`/api/v1/ai/search`（+ 登录/注册/验证码/找回密码/支付回调） | 尝试解析：有效则注入 `UserContext`；无效/缺失**静默放行** |
| ③ 强制认证 | 其余全部 `/api/v1/**` | 无有效 Token → **HTTP 401**（文案分两类，见下） |

校验顺序：Token 有效性（401）→ `@RequireRole` 角色（403）→ 业务层归属（203）。

**401 文案分两类（与第 5、6 章对齐）**：`resolveClaims()` 会把"没带凭证"与"带了但不可用"都塌缩成 `null`，因此拦截器额外用 `hasCredential(request)` 单独判断"是否携带"，据此选择错误码：

| 场景 | ErrorCode | msg |
| :--- | :--- | :--- |
| **未携带**凭证（无 `Authorization` 头 / 非 `Bearer` 前缀 / Token 为空） | `NOT_LOGIN` | 请先登录 |
| **携带但不可用**（登出黑名单命中 / JWT 已过期 / 签名被篡改 / 格式错误 / payload 缺字段） | `UNAUTHORIZED` | 登录已失效，请重新登录 |
| Token version 不匹配（改密/换绑/找回密码/封禁） | `UNAUTHORIZED` | 登录已失效，请重新登录 |
| 用户已被封禁（版本比对通过后的兜底） | `UNAUTHORIZED` | 登录已失效，请重新登录 |

> 二者 HTTP 状态码都是 401，前端只需判断 `code === 401` 即可跳登录页，不依赖 `msg` 文案。

**Token version 校验（防绕过）**：拦截器统一调用 `TokenVersionService#currentVersion(userId)` 读取版本——该 Key 不存在时由服务"初始化为 1 并写回"，**绝不因为 Key 缺失而跳过比对放行**（否则 Redis 被清空后旧 Token 会绕过封禁/改密失效机制）。比对通过后仍会兜底查 `user:status:{userId}` 确认用户未被封禁，双保险。

**单 Token 注销的故障兜底**：退出登录同时写 Redis `jwt:blacklist:{token}` 与进程内 `LocalJwtBlacklist`（LRU 上限 10000 条、TTL 2 小时，与 JWT 有效期同阶）。Redis 不可用时拦截器回退查进程内缓存，保证"已登出 Token"仍被拒绝。

---

## 四、其它已落实的关键规范

### 4.1 数据与业务一致性

- **金额安全**：下单请求体无 `amount` / `tradeType`，后端读商品快照，`amount = product_price × quantity`（BigDecimal）。
- **幂等**：下单走 Redis Lua 原子校验删除防重 Token；状态变更接口全部判断影响行数（0 行 → 已处于目标状态返回 `200 + "请勿重复操作"`，否则 `209`）。
- **权限归属**：买家 `user_id` 校验；卖家 `seller_id` 校验（面交直接完成 `finishFaceToFace` 必须 `seller_id`）；发货 SQL 限定 `trade_type IN (2,3)`。
- **逻辑删除**：全局配置 `logic-delete-field: isDeleted`；订单回看已删除商品走自定义 SQL（`selectByIdIgnoreLogicDelete`），未使用 `@InterceptorIgnore`。
- **写路径缓存一致性（已全量覆盖）**：`ProductCacheService` 作为删除缓存的统一入口，遵守"先更新 DB，再删除缓存"。覆盖点：商品发布/编辑/下架/删除（`ProductServiceImpl`）、分类迁移（`CategoryServiceImpl`）、**审核 3→1/3→0 与强制下架（`AdminServiceImpl`）**、**封禁批量下架（`AdminServiceImpl`）**、**CAS 扣减与回补的售罄联动（`StockService`）**。删除失败仅 `log.warn`，由短 TTL 兜底。
- **搜索结果缓存与熔断（5.4.4）**：商品搜索带 60 秒缓存（+最多 10 秒随机抖动防雪崩）与 500ms 超时熔断（连续 5 次超时开闸 30 秒，期间直查 DB 或降级 LIKE），两块能力各有独立开关；关键词检索已由 LIKE 升级为 MySQL FULLTEXT（`V2` 迁移，ngram 解析器），应用层保留 LIKE 作为自动降级路径。
- **通知的事务边界（6.0.4 · M3）**：通知写入统一在事务提交后发送（`TransactionSynchronization#afterCommit` + 有界线程池），避免"业务回滚了消息却已发出"。
- **用户状态缓存（6.0.4 · M4）**：`user:status:{userId}` 带短 TTL 且**以 DB 为准**读取，Redis 与库不一致时不会把封禁用户放进来。
- **审计日志降级**：`AdminAuditService#record` 内部捕获异常 → `log.error("[ALERT] ...")`，不影响业务提交。
- **日志脱敏**：全局检索确认无任何打印明文密码的语句；日志格式含 `requestId`（`RequestIdFilter` 注入 MDC）。
- **线程池**：`AsyncConfig` 核心 8 / 最大 16 / 队列 200 / `CallerRunsPolicy`，未使用 `Executors`。

### 4.2 安全加固（6.0.x 批次）

| 批次 | 内容 |
| :--- | :--- |
| 6.0.1 | S1：`JWT_SECRET` 长度与默认值启动断言（prod fail-fast）；S2：prod 禁止 `app.email.skip=true`，且**仅 dev 才把验证码回显到响应体** |
| 6.0.2 | S3：支付回调必须带 `HMAC-SHA256(orderNo\|tradeNo\|timestamp)` 签名且时间戳偏差 <= 300 秒，密钥未配置即一律拒绝；M1：`IpUtils` 只在直连对端命中 `app.security.trusted-proxies` 时才采信 `X-Forwarded-For`（修前换个 header 就能刷 IP 维度失败额度），登录失败计数按规范化后的用户名归档；M7-a：prod 关闭 Swagger/knife4j；M7-b：prod 强制 Redis 密码；随机数一律用 CSPRNG（不再用 `Get-Random`） |
| 6.0.3 | B1：库存回补幂等凭证（见 §三.1）；B2：封禁卖家时其在售商品一并下架；M7：统一安全响应头（`SecurityHeadersFilter`：`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy` 等） |
| 6.0.4 | M3 / M4：通知与用户状态缓存的事务、时效与一致性（见 §4.1） |
| 6.0.5.1 | M2：面交订单由**卖家确认收款**才完成 1→3（前端配套按钮）；M5：已售罄商品编辑后重新进入待审核 |
| 6.0.5.2 | M6：上传加固（先按文件头读图片尺寸并限制总像素，再落地；单用户文件数 / 总容量配额，删除与配额回滚成对；换图/换头像时清理旧文件）；定时任务加固（参数化 SQL、ShedLock 占用时长可配、超时取消每单独立事务、面交自动确认兜底） |

上传与静态访问约定：文件存到 `app.storage.local.base-path`（默认 `./uploads`），访问前缀
`app.storage.local.url-prefix`（默认 `/static/uploads`，由 `WebMvcConfig#addResourceHandlers` 映射，
且已列入完全公开路径）；商品图落盘为 `uploads/product/{userId}/{uuid}.jpg`（仅 8 位 JPEG，含 WebP 输入的转码/拒收策略见
`LocalStorageImpl`），列表页用的缩略图是同目录的 `_thumb.jpg`（`ProductListVO.thumbUrl`）；
`GlobalExceptionHandler` 把 `MaxUploadSizeExceededException` 映射为 `code=100`。

### 4.3 前端工程规范

- **样式隔离**：`verify:styles` 门禁断言业务组件不直接写裸色值，统一走 `src/assets/styles/variables.scss` 的设计变量。
- **分包**：ECharts 用 `manualChunks`（**对象形式**，chunk 名稳定）单独拆包，业务改动不再让 583 kB 的图表产物缓存整块失效；`verify:chunks` 按文件名断言拆分结果。
- **日期控件**：统一 `el-date-picker` / `el-date-editor` 系列，避免样式与交互不一致。
- **图片降级**：`ProductImage` 提供 `fallbackSrc`，列表卡片优先用 `thumbUrl`，避免首屏拉全尺寸大图。
- **错误提示**：`utils/request.js` 的 `baseURL` 为相对路径 `/api/v1`（由 Vite 代理转发），连接失败提示按 dev / 生产区分文案，不在生产提示本地开发地址。

---

## 五、接口与前端模块清单

### 5.1 Controller（12 个 / 61 个端点）

| Controller | 端点数 | 职责 |
| :--- | :--- | :--- |
| `AuthController` | 5 | 注册、登录、邮箱验证码、找回密码、退出 |
| `UserController` | 4 | 个人资料、改密、头像、公开主页 |
| `ProductController` | 7 | 发布、编辑、下架/删除、列表、详情、我的商品 |
| `CategoryController` | 1 | 分类列表（含缓存） |
| `FavoriteController` | 4 | 收藏 / 取消收藏 / 我的收藏 / 状态查询 |
| `OrderController` | 15 | 下单、支付、回调、发货、收货、面交确认、取消、退款申请/同意/拒绝、列表与详情 |
| `NotificationController` | 4 | 消息列表、未读数、已读、全部已读 |
| `UploadController` | 1 | 图片上传（商品图 / 头像） |
| `AiSearchController` | 1 | 智能搜索（当前为关键词+LIKE/FULLTEXT 实现，RAG 见待办） |
| `AdminController` | 10 | 商品审核、强制下架、用户封禁/解封、订单管理、强制退款、分类与审计日志 |
| `AdminCategoryController` | 4 | 分类增删改与迁移 |
| `AdminStatsController` | 5 | 管理端数据统计（概览 / 趋势 / 分类分布 / 热销 / 用户增长） |

端点的范围、参数、响应与错误码以 `docs/API_INTERFACE_SPEC.md` 为准；`api-tests.http` 是可直接执行的联调用例集。

### 5.2 前端视图（17 个 / 27 条路由）

- 公共：首页、商品详情、登录、注册、无权限页、404 页
- 买家/卖家：发布商品、发布成功、我的商品、编辑商品、确认订单、下单成功、我的订单、订单详情、个人中心、我的收藏、消息中心
- 管理端（角色守卫 `adminGuard`）：数据统计、商品审核、用户管理、订单管理、分类管理、审计日志

---

## 六、文档索引与同步约定

| 文档 | 定位 | 什么时候必须改 |
| :--- | :--- | :--- |
| `README.md` | 项目门面：技术栈、跑起来的步骤、环境变量、核心约束与安全特性清单 | 启动方式、环境变量、技术栈版本、交付物清单变化时 |
| `docs/API_INTERFACE_SPEC.md` | 接口契约（含错误码与路径语义），前端与论文的对接依据 | 任何端点新增/删除、请求或响应字段变化、错误码语义变化时 |
| `PROJECT_CONTEXT.md` | 分章设计说明 + 变更记录 + 错误码表，文档批量对齐的基准 | 每批完成后追加变更记录行；设计口径变化时改对应章节 |
| `SYSTEM_PROMPT.md` | 给后续开发者/AI 的工程约定（编码、验证、常见坑） | 出现新的可复用约定或踩坑时 |
| `BATCH_TEMPLATE.md` | 每批交付报告的固定骨架 | 报告结构本身需要调整时 |
| `系统测试.txt` | 按批次累计的真机验证记录 | 每批验证完成后追加一段（标注批次与日期） |
| `docs/自审报告-2026-09-19.md` | 安全问题台账（含"未能验证的部分"） | 修复问题后同步勾选（统一标 `已修复（批次号）`），未修的保持原状 |
| `api-tests.http` | 可直接执行的端到端验收用例集 | 端点新增/变更时补用例 |
| `frontend/README.md` | 前端工程说明（目录树、后端对接坑、设计规范、测试与门禁） | 前端目录结构、页面范围、测试数字变化时 |

同步规则（每批收尾必查）：

1. 改了端点 → `docs/API_INTERFACE_SPEC.md` 与 `api-tests.http` 同步；
2. 改了配置项 / 环境变量 / 启动方式 → `README.md` 与 `PROJECT_CONTEXT.md` 同步；
3. 改了设计口径（状态机、错误码、缓存策略、安全策略）→ `PROJECT_CONTEXT.md` 对应章节 + `README.md` §三/§四 同步；
4. 每批结束 → `PROJECT_CONTEXT.md` 头部追加版本行、`系统测试.txt` 追加验证记录、`docs/自审报告` 勾选状态同步；
5. 涉及新技术约定 → `SYSTEM_PROMPT.md` 追加约定条目。

---

## 七、待办与后续步骤

- [x] 真实编译 + 全量单测（JDK 21 `javac` + JUnit Platform：42 个测试类 / 222 个用例全绿）
- [x] Service 层单测覆盖（状态机 0→1→2→3 / 0→1→3 / 0→3、退款分支、冻结解冻、库存回补幂等断言）
- [x] Vue 3 前端工程（登录、首页、详情、下单、订单列表、消息中心、管理端全部页面）
- [x] 收藏列表的 `productStatus` 失效标注（已删除 / 已下架 / 已售罄三态灰化 + 角标 + 不可下单）
- [ ] 1000 线程并发抢购测试（`CountDownLatch` + `ThreadPoolExecutor`，论文核心数据）
- [ ] `MinioStorageImpl`（可选实现，需自行引入 minio 依赖并设 `STORAGE_TYPE=minio`）
- [ ] RAG 智能导购（Spring AI + ES 8.x + BGE-M3）替换 `AiSearchServiceImpl` 的关键词实现
- [ ] 支付回调金额校验（当前校验签名与时间窗，未回查订单金额一致性）
- [ ] 自审报告 Minor 1–9 的收尾（`UNFINISHED_ORDER_STATUS` 缺 5、冻结集合缺 7、超时取消未区分 `trade_type`、`quantity` 缺 `@Max`、CR/LF 清洗、验证码未按场景隔离、`User.password` 需 `@JsonIgnore`、图片 URL 协议白名单等）
