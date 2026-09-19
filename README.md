# 校园二手交易平台 · 后端骨架（V26 对齐）

Java 21 + Spring Boot 3 + MyBatis-Plus + MySQL 8.0 + Redis + Flyway + ShedLock。
统一包名 `com.campus.market`，接口统一前缀 `/api/v1/`。

---

## 一、交付内容

| 交付物 | 路径 |
| :--- | :--- |
| 接口清单（路径/方法/请求/响应/错误码/路径语义） | `docs/API_INTERFACE_SPEC.md` |
| Flyway 建表脚本（8 张表） | `src/main/resources/db/migration/V1__init.sql` |
| Maven 依赖 | `pom.xml` |
| 应用配置（dev/prod + 环境变量） | `src/main/resources/application.yml`、`application-dev.yml`、`application-prod.yml` |
| Entity（8 张表 + 2 个状态枚举） | `src/main/java/com/campus/market/entity/` |
| Mapper（含 CAS 扣减/回补 + 全部状态机 SQL） | `src/main/java/com/campus/market/mapper/` |
| 全局异常 + 统一响应 + 错误码 | `common/exception/`、`common/result/`、`common/enums/` |
| 自定义拦截器（三类路径语义 + Token version） | `security/AuthInterceptor.java`、`config/WebMvcConfig.java` |
| Controller（11 个） | `src/main/java/com/campus/market/controller/` |
| Service 接口 + 实现 | `src/main/java/com/campus/market/service/` |
| 配置类（MyBatis-Plus / Jackson / CORS / 线程池 / ShedLock / 密码） | `src/main/java/com/campus/market/config/` |

---

## 二、构建与运行

> ⚠️ 本骨架在**无 JDK / Maven 的环境**中生成，仅做了静态一致性校验（import 可解析、SQL 文案逐字比对、注解与字段映射核对）。
> 首次拉取后请务必执行一次真实编译：

```bash
mvn -q clean compile
```

运行（需本地 MySQL 8.0 + Redis）：

```bash
# 1. 建库（Flyway 会自动执行 V1__init.sql，无需手工建表）
mysql -uroot -p -e "CREATE DATABASE campus_market DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 2. 启动（敏感配置走环境变量，均有 dev 默认值）
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动后：

| 地址 | 说明 |
| :--- | :--- |
| `http://127.0.0.1:8080/actuator/health` | 健康检查（完全公开） |
| `http://127.0.0.1:8080/doc.html` | knife4j 接口文档（完全公开） |
| `http://127.0.0.1:8080/swagger-ui.html` | Swagger UI（完全公开） |

### 关键环境变量

| 变量 | 说明 | 默认（dev） |
| :--- | :--- | :--- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | 数据库 | 127.0.0.1:3306/campus_market, campus_user/123456 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis | 127.0.0.1:6379 |
| `JWT_SECRET` | **必须 >= 32 字节**，生产强制注入 | dev 占位值 |
| `CORS_ALLOWED_ORIGINS` | 前端域名，**严禁 `*`** | http://localhost:5173 |
| `EMAIL_SKIP` | 验证码降级开关，答辩环境 `true` | true |
| `CAMPUS_EMAIL_SUFFIXES` | 校园邮箱后缀允许列表 | @stu.edu.cn,@campus.edu.cn |
| `STORAGE_TYPE` | `local`(默认) / `minio` | local |
| `SNOWFLAKE_WORKER_ID` | **order_no 雪花算法必须配置，多实例唯一** | 1 |
| `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP（`EMAIL_SKIP=false` 时才使用） | - |

---

## 三、三个核心技术约束的实现位置

### 1. CAS 扣减库存与回补

- **扣减**：`ProductMapper#deductStock`
  ```sql
  UPDATE tb_product SET stock = stock - #{quantity},
         status = CASE WHEN stock - #{quantity} = 0 THEN 2 ELSE status END
   WHERE id = #{id} AND stock >= #{quantity} AND status = 1 AND is_deleted = 0
  ```
- **回补（统一入口）**：`ProductMapper#restoreStock` + `StockService#restore`
  ```sql
  UPDATE tb_product SET stock = stock + #{quantity},
         status = CASE WHEN status = 2 THEN 1 ELSE status END
   WHERE id = #{id} AND is_deleted = 0
  ```
- 调用点（**全部与订单状态更新同一事务，且仅影响行数 > 0 时执行**）：
  1. 买家主动取消 0→4：`OrderServiceImpl#cancel`
  2. 超时自动取消 0→4：`ScheduledTasks#cancelTimeoutOrders`
  3. 管理端解冻转取消 5→4：`AdminServiceImpl#unfreezeOrder`
  4. 卖家同意退款 6→4：`OrderServiceImpl#agreeRefund`
  5. 管理员强制退款 6/7→4：`AdminServiceImpl#forceRefund`
  6. 封禁冻结 0/1/2/6→5：`AdminServiceImpl#banUser`
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

`security/AuthInterceptor.java` + `common/constant/PathConstants.java`：

| 类别 | 路径 | 行为 |
| :--- | :--- | :--- |
| ① 完全公开 | `/actuator/health`、`/actuator/prometheus`、`/swagger-ui/**`、`/v3/api-docs/**`、`/doc.html`、`/favicon.ico`、`/error`、`/static/**` | 直接 `return true`，**不解析 Token** |
| ② 可选认证 | `/api/v1/product/detail/**`、`/api/v1/product/list`、`/api/v1/category/list`、`/api/v1/ai/search`（+ 登录/注册/验证码/支付回调） | 尝试解析：有效则注入 `UserContext`；无效/缺失**静默放行** |
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

**Token version 校验（防绕过）**：拦截器统一调用 `TokenVersionService#currentVersion(userId)` 读取版本——该 Key 不存在时由服务"初始化为 1 并写回"，**绝不因为 Key 缺失而跳过比对放行**（否则 Redis 被清空后旧 Token 会绕过封禁/改密失效机制）。比对通过后仍会兜底查 `user:status:{userId}`（未命中再查库）确认用户未被封禁，双保险。

**单 Token 注销的故障兜底**：退出登录同时写 Redis `jwt:blacklist:{token}` 与进程内 `LocalJwtBlacklist`（LRU 上限 10000 条、TTL 2 小时，与 JWT 有效期同阶）。Redis 不可用时拦截器回退查进程内缓存，保证"已登出 Token"仍被拒绝。

---

## 四、其它已落实的关键规范

- **金额安全**：下单请求体无 `amount` / `tradeType`，后端读商品快照，`amount = product_price × quantity`（BigDecimal）。
- **幂等**：下单走 Redis Lua 原子校验删除防重 Token；状态变更接口全部判断影响行数（0 行 → 已处于目标状态返回 `200 + "请勿重复操作"`，否则 `209`）。
- **权限归属**：买家 `user_id` 校验；卖家 `seller_id` 校验（面交直接完成 `finishFaceToFace` 必须 `seller_id`）；发货 SQL 限定 `trade_type IN (2,3)`。
- **逻辑删除**：全局配置 `logic-delete-field: isDeleted`；订单回看已删除商品走自定义 SQL（`selectByIdIgnoreLogicDelete`），未使用 `@InterceptorIgnore`。
- **写路径缓存一致性（已全量覆盖）**：`ProductCacheService` 作为删除缓存的统一入口，遵守"先更新 DB，再删除缓存"。覆盖点：商品发布/编辑/下架/删除（`ProductServiceImpl`）、分类迁移（`CategoryServiceImpl`）、**审核 3→1/3→0 与强制下架（`AdminServiceImpl`）**、**封禁批量下架（`AdminServiceImpl`）**、**CAS 扣减与回补的售罄联动（`StockService`）**。删除失败仅 `log.warn`，由短 TTL 兜底。
- **上传静态访问**：`WebMvcConfig#addResourceHandlers` 把 `app.storage.local.url-prefix`（默认 `/static/uploads`）映射到 `app.storage.local.base-path` 磁盘目录；该前缀已列入完全公开路径。
- **上传超限错误码**：`GlobalExceptionHandler` 单独映射 `MaxUploadSizeExceededException` → `code=100`。
- **缓存 Key 统一收口**：全部 Key 常量集中在 `RedisKeys`（含分类列表 `product:category:list`）。
- **邮箱验证码失败计数不可重置**：`email:fail:{email}` 在重新获取验证码时不删除，1 小时累计 5 次后写入 `email:lock:{email}`，锁定**固定 30 分钟**（返回 107）；`email:fail`（1 小时窗口）与锁 Key（固定 30 分钟）职责分离，不拿窗口剩余时间当锁定时长。
- **弱密码表**：`WeakPasswordConstants` 内置 100 条硬编码列表，未引入外部字典。
- **审计日志降级**：`AdminAuditService#record` 内部捕获异常 → `log.error("[ALERT] ...")`，不影响业务提交。
- **日志脱敏**：全局检索确认无任何打印明文密码的语句；日志格式含 `requestId`（`RequestIdFilter` 注入 MDC）。
- **线程池**：`AsyncConfig` 核心 8 / 最大 16 / 队列 200 / `CallerRunsPolicy`，未使用 `Executors`。

---

## 五、待办与后续步骤（未包含在本骨架内）

- [ ] `mvn -q clean compile` 真实编译验证（本机无 JDK/Maven，未执行）
- [ ] 1000 线程并发抢购测试（`CountDownLatch` + `ThreadPoolExecutor`，论文核心数据）
- [ ] Service 层单测全覆盖（状态机 0→1→2→3 / 0→1→3 / 0→3、退款分支、冻结解冻、库存回补断言）
- [ ] `MinioStorageImpl`（可选实现，需自行引入 minio 依赖并设 `STORAGE_TYPE=minio`）
- [ ] 收藏列表的 `product_status` 前端失效标注联调
- [ ] RAG 智能导购（Spring AI + ES 8.x + BGE-M3）替换 `AiSearchServiceImpl`
- [ ] Vue 3 前端工程（登录、首页、详情、下单、订单列表、消息中心、管理端审计日志）
