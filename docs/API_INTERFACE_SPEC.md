# 校园二手交易平台 · 接口清单（V26 封版对齐）

> 统一前缀：`/api/v1/`
> 统一响应体：`Result<T> { code:int, msg:String, data:T }`
> 业务接口一律 **HTTP 200**，业务结果由 `body.code` 区分；**仅未登录 / Token 失效返回 HTTP 401**。
> 分页请求参数统一：`page`（默认 1，`@Min(1)`）、`size`（默认 10，`@Max(100)`）；校验失败 → `code=100`。
> 路径语义：`公开`=拦截器完全跳过；`可选认证`=有 Token 则解析注入，无/失效均放行；`强制认证`=必须有效 Token，否则 HTTP 401。

---

## 0. 通用约定

### 0.1 错误码（第 5 章）

| code | 类型 | 说明 |
| :--- | :--- | :--- |
| 200 | 成功 | 操作成功 |
| 100 | 参数校验（通用） | Spring Validation 注解校验失败，msg 携带字段错误 |
| 101 | 认证 | 用户名或密码错误 |
| 102 | 参数校验（业务层） | 邮箱格式错误 |
| 103 | 参数校验（业务层） | 验证码错误或已过期 |
| 104 | 安全 | 账号已锁定（连续失败 5 次）或 IP 已限流 |
| 105 | 系统 | 邮件发送失败 |
| 106 | 参数校验（业务层） | 验证码发送过于频繁（60 秒限流） |
| 107 | 安全 | 验证码服务已锁定（1 小时内失败 5 次，锁 30 分钟） |
| 201 | 业务逻辑 | 库存不足 |
| 202 | 业务逻辑 | 重复下单 |
| 203 | 业务逻辑 | 无权操作该订单/商品/通知 |
| 204 | 业务逻辑 | 商品不存在或已下架 |
| 205 | 业务逻辑 | 用户已被封禁 |
| 206 | 业务逻辑 | 买家在退款被拒(7)状态下尝试支付/收货 |
| 207 | 业务逻辑 | 商品有未完成订单，禁止删除 |
| 208 | 业务逻辑 | 分类下存在商品，禁止删除 |
| 209 | 业务逻辑 | 当前状态不允许此操作（状态机冲突） |
| 401 | 认证 | 未登录或 Token 失效（HTTP 401） |
| 403 | 授权 | 无管理员权限 |
| 500 | 系统 | 服务器内部错误 |

### 0.2 分页响应结构

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "total": 128,
    "pages": 13,
    "current": 1,
    "size": 10,
    "records": []
  }
}
```

### 0.3 订单状态字典

`0-待支付 1-已支付待发货 2-已发货待收货 3-已完成 4-已取消 5-已冻结 6-退款申请中 7-退款被拒`

---

## 1. 认证与用户 `/api/v1/auth` `/api/v1/user`

| # | 方法 | 路径 | 语义 | 请求 | 响应 |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 1.1 | POST | `/api/v1/auth/register` | 公开 | body: `username`(学号,50) `password`(8-20 强密码) `nickname?` `email`(校园邮箱) `emailCode` | `data`: `{userId}`；失败 100/102/103/107 |
| 1.2 | POST | `/api/v1/auth/login` | 公开 | body: `username` `password` | `data`: `{token, userId, username, nickname, avatar, role, expiresIn}`；失败 101/104/205 |
| 1.3 | POST | `/api/v1/auth/logout` | 强制认证 | header: `Authorization` | `data`: null；单 Token 黑名单 `jwt:blacklist:{token}`，TTL=Token 剩余时间 |
| 1.4 | GET | `/api/v1/auth/email-code?email=&scene=` | 公开 | `scene`: `REGISTER`/`RESET_PASSWORD`/`BIND_EMAIL` | `data`: `{skip:true, code:"123456"}`（`email.skip=true` 时直接返回）；失败 102/106/107/105 |
| 1.5 | POST | `/api/v1/auth/reset-password` | 公开 | body: `email` `emailCode` `newPassword` | `data`: null；成功后 `user:token:version+1`；失败 103/107 |
| 1.6 | GET | `/api/v1/user/profile` | 强制认证 | - | `data`: `UserVO`（password 永不返回） |
| 1.7 | PUT | `/api/v1/user/profile` | 强制认证 | body: `nickname` `phone` `avatar` | `data`: null |
| 1.8 | PUT | `/api/v1/user/password` | 强制认证 | body: `oldPassword` `newPassword` | `data`: null；成功后 `version+1`；新密码不得与旧密码相同 |
| 1.9 | POST | `/api/v1/user/change-email` | 强制认证 | body: `newEmail` `emailCode` `password` | `data`: null；成功后 `version+1` |
| 1.10 | POST | `/api/v1/auth/refresh` | 预留（P2） | - | 双令牌机制，MVP 不实现 |

## 2. 商品分类 `/api/v1/category`

| # | 方法 | 路径 | 语义 | 请求 | 响应 |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 2.1 | GET | `/api/v1/category/list` | **可选认证** | - | `data`: `List<CategoryVO>{id,name,sort}`（按 sort 升序） |
| 2.2 | POST | `/api/v1/admin/category` | 强制认证 + `@RequireRole(1)` | body: `name` `sort` | `data`: `{id}` + 审计日志 `CREATE_CATEGORY` |
| 2.3 | PUT | `/api/v1/admin/category/{id}` | 强制认证 + `@RequireRole(1)` | body: `name` `sort` | `data`: null + 审计 `UPDATE_CATEGORY` |
| 2.4 | DELETE | `/api/v1/admin/category/{id}` | 强制认证 + `@RequireRole(1)` | - | `data`: null；分类下有商品 → 208 + 审计 `DELETE_CATEGORY` |
| 2.5 | PUT | `/api/v1/admin/category/migrate` | 强制认证 + `@RequireRole(1)` | body: `fromCategoryId` `toCategoryId` | `data`: `{movedCount}` 级联迁移 |

## 3. 商品 `/api/v1/product`

| # | 方法 | 路径 | 语义 | 请求 | 响应 |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 3.1 | GET | `/api/v1/product/list` | **可选认证** | `keyword?` `categoryId?` `minPrice?` `maxPrice?` `conditionLevel?` `tradeType?` `sortBy?`(price/ create_time) `order?`(asc/desc) `page` `size` | `data`: 分页 `ProductListVO`；Stream 转 VO 并剔除卖家 `phone`/`email`；`is_deleted=0` 且 `status=1`（本人/管理员可见性提升见 3.3） |
| 3.2 | GET | `/api/v1/product/detail/{id}` | **可选认证** | - | `data`: `ProductDetailVO`；可见性：游客 `status=1`；登录非卖家/非管理员 `status IN (0,1,2)`；卖家本人全部状态；管理员全部状态；否则 204 |
| 3.3 | POST | `/api/v1/product` | 强制认证 | body: `categoryId` `title` `description` `price` `stock` `conditionLevel` `tradeType` `tradeLocation?` `imageUrls[]`(1-9) | `data`: `{id}`；落库 `status=3` 待审核；校验 title 1-100、price>0、condition_level 1-4 |
| 3.4 | PUT | `/api/v1/product/{id}` | 强制认证 | body 同上（全量更新语义） | `data`: null；关键字段变更 → `status=3`；`status=2` 且新库存>0 → `status=1` |
| 3.5 | DELETE | `/api/v1/product/{id}` | 强制认证 | - | `data`: null；存在未完成订单(0/1/2/6/7) → 207 |
| 3.6 | GET | `/api/v1/product/my` | 强制认证 | `status?` `page` `size` | `data`: 分页 `ProductListVO`（含待审核） |
| 3.7 | PUT | `/api/v1/product/off-shelf/{id}` | 强制认证 | - | `data`: null；卖家下架自己的商品（`status=1→0`） |
| 3.8 | POST | `/api/v1/upload/image` | 强制认证 | `multipart/form-data`: `file` | `data`: `{url}`；≤5MB、魔数校验、白名单 jpg/jpeg/png/webp、最大边长 8192px、总像素 ≤5000 万；UUID 重命名 `product/{userId}/{uuid}.jpg` |
| 3.9 | GET | `/api/v1/product/favorite/list` | 强制认证 | `page` `size` | 见 5. 收藏 |

## 4. 订单 `/api/v1/order`

> 下单 body 严禁传 `amount` / `trade_type`：`amount = product_price × quantity` 后端计算，`trade_type` 从商品读取并快照。

| # | 方法 | 路径 | 语义 | 请求 | 响应 |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 4.1 | GET | `/api/v1/order/token` | 强制认证 | - | `data`: `{token:uuid}`；Redis `order:token:{userId}:{uuid}`，TTL 5 分钟 |
| 4.2 | POST | `/api/v1/order` | 强制认证 | header/body: `orderToken`；body: `productId` `quantity`(`@Min(1)`) `address?`(trade_type=2/3 时 `@NotBlank`) | `data`: `{orderId, orderNo, amount, status}`；Lua 原子校验删除 Token，失败 202；CAS 扣减失败 201；商品异常 204 |
| 4.3 | GET | `/api/v1/order/list` | 强制认证 | `status?` `role?`(buyer/seller) `page` `size` | `data`: 分页 `OrderVO`（含 `product_title` 快照） |
| 4.4 | GET | `/api/v1/order/detail/{id}` | 强制认证 | - | `data`: `OrderDetailVO`；归属校验失败 203；商品已逻辑删除时用自定义 SQL 绕过逻辑删除取商品信息 |
| 4.5 | PUT | `/api/v1/order/pay/{id}` | 强制认证（买家） | - | `status: 0→1`，`pay_time=NOW()`；冲突 209 |
| 4.6 | POST | `/api/v1/order/pay/callback` | 公开（**HMAC-SHA256 验签**，批次 6.0.2 · S3） | body: `orderNo` `tradeNo` `timestamp`(毫秒) `sign`(**HMAC-SHA256 十六进制小写**) | 校验顺序：时间戳在 `app.pay.timestamp-window-seconds`(默认 300s) 内 → `sign` 非空 → HMAC 匹配 → 订单存在；任一失败 **code=100「回调签名校验失败」且不改状态**。`sign = HMAC-SHA256(orderNo+"|"+tradeNo+"|"+timestamp, PAY_CALLBACK_SECRET)`；通过后按 `order_no + 回调流水号` 幂等去重。未配置 `PAY_CALLBACK_SECRET` 时回调一律拒绝（fail-closed）。**遗留：payload 不含金额，接真实网关前必须补** |
| 4.7 | PUT | `/api/v1/order/cancel/{id}` | 强制认证（买家） | `reason?` | `status: 0→4`，`cancel_by=买家ID` + **库存回补**；买家仅可取消 status=0 |
| 4.8 | PUT | `/api/v1/order/ship/{id}` | 强制认证（卖家） | - | `status: 1→2`，`ship_time=NOW()`，仅 `trade_type IN (2,3)`；面交发货 → 209 |
| 4.9 | PUT | `/api/v1/order/receive/{id}` | 强制认证（买家） | - | 邮寄 `2→3`；面交 `1→3`（`trade_type=1`）；`finish_time=NOW()` |
| 4.10 | PUT | `/api/v1/order/finish-face/{id}` | 强制认证（**卖家 seller_id 校验**） | - | 面交直接完成 `0→3`；买家调用 → 203 |
| 4.11 | POST | `/api/v1/order/refund/apply/{id}` | 强制认证（买家） | `reason` | `status IN (1,2) → 6`，`refund_apply_time=NOW()`；7 状态再操作 → 206 |
| 4.12 | PUT | `/api/v1/order/refund/agree/{id}` | 强制认证（卖家） | - | `6→4`，`cancel_time=NOW()` + **库存回补** |
| 4.13 | PUT | `/api/v1/order/refund/reject/{id}` | 强制认证（卖家） | `rejectReason` | `6→7`，`refund_reject_time=NOW()`；3 天后定时任务自动恢复 1/2 |
| 4.14 | GET | `/api/v1/order/refund/list` | 强制认证 | `role?` `page` `size` | 退款申请列表（status=6/7） |

## 5. 收藏 `/api/v1/favorite`

| # | 方法 | 路径 | 语义 | 请求 | 响应 |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 5.1 | POST | `/api/v1/favorite/{productId}` | 强制认证 | - | `data`: null；唯一键 `uk_user_product` 幂等；重复收藏返回 200 |
| 5.2 | DELETE | `/api/v1/favorite/{productId}` | 强制认证 | - | `data`: null；**物理删除** |
| 5.3 | GET | `/api/v1/favorite/list` | 强制认证 | `page` `size` | `data`: 分页 `FavoriteVO`；**不过滤已删除/已下架商品**，返回 `productStatus`、`isDeleted` |
| 5.4 | GET | `/api/v1/favorite/check/{productId}` | 强制认证 | - | `data`: `{favorited:boolean}` |

## 6. 站内信 `/api/v1/notification`

| # | 方法 | 路径 | 语义 | 请求 | 响应 |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 6.1 | GET | `/api/v1/notification/list` | 强制认证 | `isRead?` `page` `size` | `data`: 分页 `NotificationVO{id,type,bizType,bizId,content,isRead,createTime}` |
| 6.2 | PUT | `/api/v1/notification/read/{id}` | 强制认证 | - | `data`: null；**必须校验 `user_id = 当前登录用户`**，否则 203 |
| 6.3 | PUT | `/api/v1/notification/read-all` | 强制认证 | - | `data`: `{updated}` |
| 6.4 | GET | `/api/v1/notification/unread-count` | 强制认证 | - | `data`: `{count}`；前端 30 秒轮询 |

## 7. 管理端 `/api/v1/admin`

> 全部要求 `@RequireRole(1)`（普通用户 → 403），且所有操作与业务 **同一事务** 写 `tb_audit_log`（写失败降级为 error 日志，业务仍提交）。

| # | 方法 | 路径 | 语义 | 请求 | 响应 / 审计 operation_type |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 7.1 | GET | `/api/v1/admin/product/audit/list` | 强制认证 + ROLE_ADMIN | `status?` `page` `size` | `data`: 分页（默认 status=3） |
| 7.2 | PUT | `/api/v1/admin/product/audit/{id}` | 强制认证 + ROLE_ADMIN | body: `pass:boolean` `reason?` | `3→1` / `3→0`（不通过通知卖家）；`APPROVE_PRODUCT` / `REJECT_PRODUCT` |
| 7.3 | PUT | `/api/v1/admin/product/force-offline/{id}` | 强制认证 + ROLE_ADMIN | `reason?` | `status=0`；`FORCE_OFFLINE` |
| 7.4 | GET | `/api/v1/admin/user/list` | 强制认证 + ROLE_ADMIN | `keyword?` `status?` `page` `size` | `data`: 分页 `AdminUserVO` |
| 7.5 | PUT | `/api/v1/admin/user/ban/{id}` | 强制认证 + ROLE_ADMIN | - | 同事务：①`status=1` ②在售商品下架 ③未完成订单冻结(0/1/2/6→5，**冻结时回补库存**) ④审计；提交后 `version+1`（重试 3 次指数退避）；`BAN_USER` |
| 7.6 | PUT | `/api/v1/admin/user/unban/{id}` | 强制认证 + ROLE_ADMIN | - | `status=0` + `version+1`；`UNBAN_USER` |
| 7.7 | GET | `/api/v1/admin/order/list` | 强制认证 + ROLE_ADMIN | `status?` `orderNo?` `page` `size` | `data`: 分页全量订单 |
| 7.8 | PUT | `/api/v1/admin/order/unfreeze/{id}` | 强制认证 + ROLE_ADMIN | body: `target`(`CANCEL`\|`COMPLETE`) | `5→4`（**+库存回补**）或 `5→3`；`UNFREEZE_ORDER` / `COMPLETE_ORDER` |
| 7.9 | PUT | `/api/v1/admin/order/force-refund/{id}` | 强制认证 + ROLE_ADMIN | `reason?` | `6/7→4` + **库存回补**；`REFUND_ORDER` |
| 7.10 | GET | `/api/v1/admin/audit-log/list` | 强制认证 + ROLE_ADMIN | `operatorId?` `operationType?` `startTime?` `endTime?` `page` `size` | `data`: 分页 `AuditLogVO` |

## 8. AI 扩展预留 `/api/v1/ai`

| # | 方法 | 路径 | 语义 | 请求 | 响应 |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 8.1 | GET | `/api/v1/ai/search` | **可选认证** | `query`(自然语言) `page` `size` | `data`: 分页 `ProductListVO`；MVP 阶段 MySQL `LIKE CONCAT('%',#{keyword},'%')` 匹配 title/description，**500ms 超时熔断 + 60 秒结果缓存**；P2 阶段替换为 Spring AI + ES dense_vector kNN |

## 9. 运维与文档（完全公开，跳过拦截器）

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/actuator/health` | 默认启用 |
| GET | `/actuator/prometheus` | **可选实现**（默认不引入 micrometer-registry-prometheus） |
| GET | `/swagger-ui/**`、`/v3/api-docs/**` | SpringDoc / knife4j |

---

## 10. 拦截器三类路径语义（拦截器实现契约）

| 类别 | 路径 | 行为 |
| :--- | :--- | :--- |
| ① 完全公开 | `/actuator/health`、`/actuator/prometheus`、`/swagger-ui/**`、`/v3/api-docs/**`（+ 静态资源 `/favicon.ico`、`/error`） | **直接 `return true`**，不解析 Token，不写 `UserContext` |
| ② 可选认证 | `/api/v1/product/detail/**`、`/api/v1/product/list`、`/api/v1/category/list`（+ `/api/v1/ai/search`） | 尝试解析 Token：存在且有效 → 注入 `UserContext`；不存在/无效/过期 → **静默放行**（不报错、不 401） |
| ③ 强制认证 | 其余全部 `/api/v1/**` | 必须携带有效 Token，否则 `throw BusinessException(401)` → HTTP 401（文案分两类，见下方） |

**强制认证路径内的 `@RequireRole(1)` 校验顺序**：先校验 Token 有效性（401），再校验角色（403），最后业务层归属校验（203）。

**401 文案分两类**：`resolveClaims()` 把"没带凭证"与"带了但不可用"都塌缩成 `null`，故拦截器用 `hasCredential(request)` 单独判断"是否携带"，据此决定错误码：

| 场景 | code | msg |
| :--- | :--- | :--- |
| **未携带**凭证：无 `Authorization` 头、非 `Bearer` 前缀、Token 为空 | 401 (`NOT_LOGIN`) | 请先登录 |
| **携带但不可用**：登出黑名单命中、JWT 已过期、签名被篡改、格式错误 | 401 (`UNAUTHORIZED`) | 登录已失效，请重新登录 |
| payload 缺少 `userId`/`role`/`version` | 401 (`UNAUTHORIZED`) | 登录已失效，请重新登录 |

两者 HTTP 状态码均为 401，前端仅需判断 `code === 401` 跳登录页，不依赖 `msg`。

**Token version 校验**：JWT payload 携带 `userId`/`role`/`version`；拦截器比对 Redis `user:token:version:{userId}`，不一致 → 401 `msg="登录已失效，请重新登录"`；版本比对失败时兜底查询 `user:status:{userId}`，若 `status=1` 直接 401（双保险）。
