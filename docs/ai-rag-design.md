# AI RAG 智能导购 · 环境准备与数据模型设计（5.6.1）

> 本批**只做审计、设计与操作清单**：不写业务代码、不装依赖、不改配置。
> 产出物就是本文档 + 用户操作清单 + 待拍板项。
> 上游依据：`PROJECT_CONTEXT.md` 第 4 章「AI 扩展预留（RAG 智能导购）」、`docs/API_INTERFACE_SPEC.md` §8.1、
> `docs/自审报告-2026-09-19.md` 功能缺失 F1。

---

## 0. 结论摘要（先看这一段）

| 事项 | 结论 |
| :--- | :--- |
| 现状 | `/api/v1/ai/search` 是 MySQL 参数化 `LIKE` 的**退化实现**（`AiSearchServiceImpl`），无缓存、无熔断；`RedisKeys.AI_SEARCH_PREFIX` 预留但**从未使用**；前端**从未调用**该接口 |
| 环境 | **ES 与 Ollama 均未安装**（9200 / 11434 未监听）；MySQL 3306、Redis 6379 正常；Docker 未装、WSL **未启用**；16 逻辑核、JDK 21（`C:\Users\chen\.jdks\ms-21.0.12`）、`curl.exe` 可用 |
| 检索库选型 | **Elasticsearch 8.x 单节点（Windows 原生 zip）+ IK 分词插件**；索引 `campus_product_v1` + 别名 `campus_product`；向量字段 `dense_vector`（`dims=1024`，cosine，HNSW） |
| 检索策略 | 硬过滤（`status=1`、未删除、价格/分类/成色/交易方式） + **BM25 ⊕ kNN 双路召回 + RRF 融合** + top-k 返回 |
| Embedding 选型（推荐） | **本地 Ollama + `bge-m3`（1024 维）**；备选：云端 API（对照实验用）、Spring AI 内置 ONNX MiniLM（384 维，中文偏弱） |
| Spring AI 取舍 | **推荐"不引入 Spring AI"**：用 ES 官方 Java Client + Spring 自带 `RestClient` 直连 Ollama HTTP API。理由：Spring AI 与本项目 Spring Boot 3.2.5 的版本矩阵需要单独核对（升级 Boot 属架构级变更），而 ES Java Client 与 Boot 版本无关、依赖更少、更易测试 |
| 一致性策略 | 写路径**双写**（与 `ProductCacheService`"先 DB 再失效缓存"同一范式） + **每日全量重建**（复用 ShedLock 任务） + ES 故障**降级到 MySQL FULLTEXT/LIKE** |
| 分批 | 5.6.1 本批（设计）→ 5.6.2 依赖与配置接入 → 5.6.3 写入与同步 → 5.6.4 检索链路 → 5.6.5 RAG 生成 + 前端 + 评测 |
| 需要你先做的事 | 装 ES + IK、装 Ollama + 拉模型、确认磁盘空间（见 §9） |
| 需要你拍板的事 | 8 项（见 §10），其中 3 项会影响论文叙述，建议优先看 |

---

## 1. 现状审计（任务 A）

### 1.1 AI 搜索接口的现有契约与实现

| 项 | 现状（已读源码核实） |
| :--- | :--- |
| 路径 | `GET /api/v1/ai/search?query=<自然语言>&page=&size=`（+ `ProductQuery` 的其它筛选项） |
| 认证 | **可选认证**（`PathConstants.OPTIONAL_AUTH_PATHS` 含 `/api/v1/ai/search`）→ 游客可用，登录则注入身份 |
| 响应 | `Result<PageResult<ProductListVO>>`（与普通列表同一 VO，前端可复用） |
| Controller | `AiSearchController`：把 `?query=` **显式映射**到 `ProductQuery.keyword`（曾因参数名不匹配静默忽略 `query`，导致"返回全部上架商品"，已修并有回归用例 3.20/3.20b） |
| Service | `AiSearchServiceImpl.search()`：`selectPage` + `lambdaQuery().eq(status=1).and(like(title) or like(description))` + 分类/价格过滤 + `createTime` 倒序 |
| 缓存/熔断 | **没有**。类注释写着"P2 阶段实现 500ms 熔断与 60s 缓存"，`RedisKeys.AI_SEARCH_PREFIX = "ai:search:"` 预留但**零引用** |
| 前端调用 | **零调用**（`frontend/src` 搜 `ai/search` / `aiSearch` 无命中）→ 目前只有后端契约与论文叙述 |
| 与普通搜索的关系 | 普通列表 `/product/list` 已有 **FULLTEXT(ngram) + LIKE 自动降级 + 60s 缓存 + 500ms 熔断**（5.4.4/5.4.5）；AI 路径**完全没有这些**，且用的是 LIKE 而非 FULLTEXT |

**审计结论**：AI 路径今天既没有语义能力，也没有接线层能力（缓存/熔断），是一条"契约已定、实现占位"的路径。这正好给了 5.6.x 一个干净的替换空间：**接口契约不变，实现整体替换**。

### 1.2 依赖现状（`pom.xml`）

- Spring Boot **3.2.5**、Java **21**、MyBatis-Plus 3.5.5、Flyway、ShedLock 5.10、jjwt 0.12.5、springdoc 2.3.0 + knife4j 4.4.0、spring-security-crypto、spring-boot-starter-mail、actuator。
- **没有** Elasticsearch 客户端、**没有** Spring AI、**没有** micrometer-registry-prometheus（`pom.xml` 里是**注释掉的可选依赖**，`management.endpoints.web.exposure.include: health` 也只暴露 health）。
- 结论：ES / 向量 / LLM 相关依赖**全部需要新增**，属于"架构级变更"，必须按项目约定先拍板（§10 第 1、4 项）。

### 1.3 现有检索能力（可复用的部分）

| 能力 | 位置 | 说明 |
| :--- | :--- | :--- |
| FULLTEXT + ngram | `V2__add_fulltext_index.sql`（`ft_product_title_desc (title, description) WITH PARSER ngram`） | 中文关键词检索已可用；AI 路径**没走**它，仍在用 LIKE |
| LIKE 自动降级 | `ProductServiceImpl.doKeywordSearch`（`DataAccessException` → LIKE） | 5.6.4 的"ES 挂了降级"应复用同一取舍：**能降级就不报错** |
| 60s 缓存 + 抖动 | `search.cache.*`（`enabled/ttl-seconds/ttl-jitter-seconds`） | 6.0.6 已确认"搜索结果 60 秒内可能搜不到刚发布的商品"，AI 检索要沿用同一语义 |
| 500ms 熔断 | `search.circuit-breaker.*`（`timeout-ms/failure-threshold/open-millis`）+ `SearchCircuitBreaker` | 一个**可复用的组件**：AI 检索的"检索层"可以直接用同一个 breaker，语义一致 |

### 1.4 数据模型现状（ES 索引的输入）

`tb_product`（`V1__init.sql`，`utf8mb4_0900_ai_ci`）：

| 列 | 类型 | 在检索里的用途 |
| :--- | :--- | :--- |
| `id` | BIGINT PK | 文档 `_id`（**Long 精度注意**：前端 JSON 里 id 是字符串，ES 里用 `long`，出参转字符串） |
| `user_id` | BIGINT（`idx_user_id`） | 卖家；列表 VO 的 `sellerId` |
| `category_id` | BIGINT（`idx_category_status_price`） | 过滤；VO 的 `categoryId` / `categoryName`（名称来自 `tb_category`） |
| `title` | VARCHAR(100) NOT NULL | **主召回字段**（BM25 权重最高）+ 向量输入 |
| `description` | TEXT | 次召回字段 + 向量输入 |
| `price` | DECIMAL(10,2) | 过滤 + 排序（ES 侧用 `scaled_float`，见 §3.2） |
| `stock` | INT | 过滤（`stock>0`）+ VO |
| `condition_level` | TINYINT | 过滤（1~4）+ VO |
| `trade_type` | TINYINT | 过滤（1/2/3）+ VO |
| `trade_location` | VARCHAR(100) | 召回辅助（"三食堂门口"这类地点词）+ VO |
| `image_urls` | JSON | 封面 / 缩略图来源（VO 的 `coverImage` / `thumbUrl`） |
| `status` | TINYINT（默认 3） | **硬过滤**：索引里只放 `status=1` |
| `create_time` / `update_time` | DATETIME | 排序（新品优先） / 增量同步游标 |
| `is_deleted` | TINYINT | 硬过滤（删除即从索引移除） |

`ProductListVO` 需要 15 个字段（含 `categoryName`、`sellerNickname`、`sellerAvatar`、`thumbUrl`）——其中**分类名与卖家昵称/头像不在 `tb_product` 里**，这直接决定了 §3.5 的"存字段 vs 回表"取舍。

---

## 2. 运行环境实测（任务 A 第 2 步，全部为本次实跑）

| 检查 | 命令 | 结果 |
| :--- | :--- | :--- |
| Elasticsearch 9200 | `Test-NetConnection 127.0.0.1 -Port 9200` | ❌ **未监听**（ES 未安装/未启动） |
| ES 传输层 9300 | 同上 | ❌ 未监听 |
| Kibana 5601 | 同上 | ❌ 未监听 |
| Ollama 11434 | `Test-NetConnection 127.0.0.1 -Port 11434` | ❌ **未监听**（Ollama 未安装/未启动） |
| MySQL 3306 | 同上 | ✅ 监听中（8.0.46，`campus_market`） |
| Redis 6379 | 同上 | ✅ 监听中（5.0.14） |
| Docker | `Get-Command docker` | ❌ 未安装（**不能用 docker-compose 起 ES**） |
| WSL | `wsl.exe -l -v` | ❌ **未安装 Linux 子系统**（`wsl.exe` 只是占位），不能走"WSL 里跑 ES" |
| `curl.exe` | `Get-Command curl.exe` | ✅ `C:\Windows\system32\curl.exe`（联调/探活用它） |
| JDK | — | ✅ 21.0.12 @ `C:\Users\chen\.jdks\ms-21.0.12`（**不在 PATH**，项目一直靠绝对路径编译） |
| Node | `Get-Command node` | ✅ `D:\nvm\nodejs\node.exe`（前端用） |
| CPU | `[Environment]::ProcessorCount` | 16 逻辑核（够跑 ES 单节点 + Ollama） |
| 磁盘 / 内存 | `Get-Volume` / CIM | ⚠️ **本会话被沙箱拒绝查询**，需你自己确认（预算见 §9.1） |

**环境结论**：这是一台"能跑，但两件外装都还没装"的 Windows 11（10.0.26200）机器。
因为 Docker 与 WSL 都不可用，**ES 与 Ollama 都必须装 Windows 原生版**（两者官方都提供），
这条会影响 §9 的操作清单与 §8 的风险。

---

## 3. Elasticsearch 索引设计（任务 B）

### 3.1 索引与别名策略

```
写入/重建 → campus_product_v1        （真实索引，mapping 变更时递增版本号）
查询      → campus_product           （别名，指向当前 v1）
```

- **为什么用别名**：重建索引（换 embedding 模型、改 mapping、改分词器）时可以先建 `v2`、灌完数据、一次性把别名切过去，**不用停服、也不用让代码改索引名**。ES 的 `_reindex` 也可以，但改写别名更简单可控。
- **单索引单分片**：本项目商品量上限 5000（`PROJECT_CONTEXT` 第 4 章），单分片足够；分片数写进索引模板（`number_of_shards: 1`、`number_of_replicas: 0`，单节点下副本无意义还会变黄）。
- **`refresh_interval`**：写路径双写时用默认 1s（用户发布后 1 秒内可搜到）；全量重建时临时设 `-1` 再 `_forcemerge`，重建更快。

### 3.2 字段映射（完整 mapping）

```json
PUT /campus_product_v1
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "refresh_interval": "1s",
    "analysis": {
      "analyzer": {
        "cm_index_analyzer": { "type": "custom", "tokenizer": "ik_max_word" },
        "cm_search_analyzer": { "type": "custom", "tokenizer": "ik_smart" }
      }
    }
  },
  "mappings": {
    "properties": {
      "id":             { "type": "long" },

      "title": {
        "type": "text",
        "analyzer": "cm_index_analyzer",
        "search_analyzer": "cm_search_analyzer",
        "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } }
      },
      "description": {
        "type": "text",
        "analyzer": "cm_index_analyzer",
        "search_analyzer": "cm_search_analyzer"
      },
      "tradeLocation": {
        "type": "text",
        "analyzer": "cm_index_analyzer",
        "search_analyzer": "cm_search_analyzer",
        "fields": { "keyword": { "type": "keyword", "ignore_above": 128 } }
      },

      "categoryId":     { "type": "long" },
      "sellerId":       { "type": "long" },
      "price":          { "type": "scaled_float", "scaling_factor": 100 },
      "stock":          { "type": "integer" },
      "conditionLevel": { "type": "byte" },
      "tradeType":      { "type": "byte" },
      "status":         { "type": "byte" },
      "createTime":     { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||epoch_millis" },

      "coverImage":     { "type": "keyword", "index": false, "doc_values": false },
      "thumbUrl":       { "type": "keyword", "index": false, "doc_values": false },

      "titleVector": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      },
      "embeddingModel": { "type": "keyword" },
      "embeddingText":  { "type": "keyword", "index": false, "doc_values": false }
    }
  }
}
```

设计要点与理由：

| 选择 | 理由 |
| :--- | :--- |
| `title`/`description` 用 **IK**（ik_max_word 索引 / ik_smart 查询） | ES 内置分析器对中文只能逐字切（`standard`），"考研数学"会被切成 4 个单字，召回与精确度都差。IK 是中文场景事实标准；**插件版本必须与 ES 版本严格一致**（§9 会写） |
| `title.keyword` 子字段 | 需要"标题完全相等/排序/聚合"时用（例如管理端对账、去重检查） |
| `price` 用 **scaled_float(100)** | 金额只需 2 位小数（`DECIMAL(10,2)`）；`scaled_float` 用整数存储、比 `double` 省一半空间且**没有浮点误差**，范围过滤/排序都正常。不要用 `text` 存价格 |
| `id` 用 **long** | 精确过滤 + 作 `_id`；出参时按项目约定**转成字符串**（雪花 Long 超出 JS 安全整数范围，前端全程字符串） |
| `coverImage` / `thumbUrl`：`index:false, doc_values:false` | 它们是"取回来展示"的，不参与检索/排序/聚合 → 关掉索引与列存省空间（这两个字段也说明：**索引里存了展示字段**，见 §3.5） |
| 向量字段 `dims=1024` | 与 BGE-M3 一致；**换模型必须重建索引**（dims 不可改） |
| `embeddingModel` + `embeddingText` | 可追溯"这条向量是哪个模型、用哪段文本算的" → 换模型/改模板时能精确找出需要重算的文档（这两个字段是本设计里最容易被忽略、但出问题时最省时间的设计） |
| `status` 仍在 mapping 里 | 虽然只灌 `status=1`，但保留字段便于将来放"售罄也进索引"（§10 待拍板项 5），并且**双写误操作时可被过滤兜住** |

⚠️ **IK 装不上时的降级**：把两个自定义 analyzer 换掉即可 ——
(1) `standard`（逐字，召回高但噪声大）；(2) `ngram`（`min_gram=2, max_gram=2`，与 MySQL 侧 `ngram_token_size=2` 对齐，**行为最接近现有 FULLTEXT**）。
**建议**：即使最终用 IK，也把 ngram 方案写进文档作为"评委机器上装不上插件"的兜底（§8 风险 1）。

### 3.3 文档样例（一条真实形状的数据）

```json
{
  "id": 359642335883169792,
  "title": "考研数学复习全书 九成新",
  "description": "只翻过前两章，无笔记，可小刀",
  "tradeLocation": "图书馆一楼大厅",
  "categoryId": 1,
  "sellerId": 9100088,
  "price": 45.00,
  "stock": 1,
  "conditionLevel": 2,
  "tradeType": 1,
  "status": 1,
  "createTime": "2026-09-19 18:30:00",
  "coverImage": "/static/uploads/product/9100088/ab12.jpg",
  "thumbUrl": "/static/uploads/product/9100088/ab12_thumb.jpg",
  "titleVector": [0.013, -0.221, "... 共 1024 维"],
  "embeddingModel": "bge-m3",
  "embeddingText": "考研数学复习全书 九成新｜只翻过前两章，无笔记，可小刀｜教材书籍｜几乎全新｜面交：图书馆一楼大厅"
}
```

### 3.4 文档 ID 与更新语义

- `_id` = `tb_product.id`（同一商品重复写入是**幂等覆盖**，天然解决"重复双写"）。
- 全量重建用 `_bulk`（每批 500 条）+ `index` 动作（不是 `create`，允许覆盖）。
- 删除 = `_bulk` 的 `delete` 动作（逻辑删除也要**真删文档**，否则 AI 检索会搜到已删除商品）。

### 3.5 存字段 vs 回表（关键取舍）

`ProductListVO` 需要 `categoryName`、`sellerNickname`、`sellerAvatar` —— 这三个字段**不在 `tb_product`**，
分别来自 `tb_category` 与 `tb_user`。

| 方案 | 做法 | 优点 | 缺点 |
| :--- | :--- | :--- | :--- |
| **甲：只存检索字段 + 回表**（推荐用于本期） | ES 只存 `id` + 过滤/排序字段 + 向量；拿到 top-k id 后按现有 `ProductServiceImpl` 的组装逻辑回 MySQL 查 VO | **单一数据源**（MySQL 永远是权威）、分类改名/改昵称**立刻生效**、无需处理敏感字段（如卖家 phone 绝不进 ES） | 多一次 `IN (...)` 查询；ES 里已被下架但 MySQL 还没同步时会出现"少几条"（可用"回表结果 < k 就补查"缓解） |
| 乙：存全量展示字段 | 把 `categoryName`/`sellerNickname`/`sellerAvatar` 也写进文档，直接由 ES 拼 VO | 一次查询出结果、快 | **一致性面变大**（分类改名、改昵称、封禁卖家都要同步）；有把敏感字段写进 ES 的风险；仍要防 `phone/email` 进文档 |

**推荐：甲（回表）**。理由：本项目量级小（5000 商品）、MySQL 是唯一权威、且项目已有成熟的 VO 组装与缓存逻辑（`ProductCacheService`），复用它能避免"第二套真相"。`coverImage`/`thumbUrl` 例外地放进文档（它们来自 `tb_product.image_urls`，属商品自身字段、且能省一次图片字段拼装）。

### 3.6 检索策略：双路召回 + RRF 融合

```text
query(自然语言)
  │
  ├─ 前置解析（可选，5.6.4 再做）：从 query 里抽出 价格上限 / 分类词 / 成色词 → 变成硬过滤条件
  │
  ├─ 路 A：BM25 关键词召回        multi_match(title^3, description^1, tradeLocation^1)  → top 50
  ├─ 路 B：kNN 向量召回          knn(titleVector, k=50, num_candidates=100)           → top 50
  │
  ├─ 硬过滤（两条路都带）：status=1 AND stock>0 AND 价格/分类/成色/交易方式（若 query 解析出）
  │
  └─ RRF 融合（rank-based，无需调权重）：score = Σ 1/(k + rank_i)，k=60（业界常用默认）
        → 排序 → 分页（top-k，本项目 k=50）→ 回表补 VO → 返回 PageResult<ProductListVO>
```

| 选择 | 理由 |
| :--- | :--- |
| **RRF 而不是加权求和** | BM25 分（通常 0~30）与 cosine 相似度（0~1）**量纲完全不同**，加权前必须归一化，而归一化方式本身又是一个要调的参数；RRF 只看"名次"，**免调参、结果稳定、论文里也好解释**（引用 RRF 原论文即可） |
| kNN 用 ES 原生 `knn`（HNSW） | ES 8.x 的 `dense_vector` 支持 `index: true` + HNSW，**不需要外部向量库**；`num_candidates` 控制召回质量/耗时折中（100 起步，量级小够用） |
| 过滤与 kNN 一起下发 | ES 8.x 的 `knn` 支持 `filter` 子句（**pre-filter**，先过滤再算距离）→ 语义正确（不会先召 50 条再被过滤掉一半） |
| top-k=50 再分页 | 语义检索的分页只在 top-k 内做（`from/size` 或 `search_after`）；5000 商品量级下 `from+size ≤ 10000` 够用，但**要写明**：超过 10000 需换 `search_after` |
| 依赖排序 | 若"新品优先"权重过高会压掉语义相关性 → 建议**默认按融合得分**，只把 `createTime` 作为**同分时的 tiebreaker**（`sort: [_score desc, createTime desc]`） |

### 3.7 索引初始化与 mapping 变更流程

1. `5.6.2` 提供 `docs/es/campus_product_v1.json`（mapping）与一条初始化命令（`curl.exe -XPUT` 或 `_bulk` 脚本）。
2. mapping **只能加字段、不能改类型**（除少数例外）→ 改类型 = 建新索引 + `_reindex`/重建 + 切别名。
3. 每次 mapping 变更都要在 `PROJECT_CONTEXT` 追加一行变更记录（沿用项目的文档同步规矩）。

---

## 4. Embedding 方案对比（任务 C）

### 4.1 候选对比

| 方案 | 模型 / 维度 | 部署方式 | 成本 | 离线可用 | 中文效果 | 对现有工程的影响 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **A 本地 Ollama + bge-m3**（推荐） | `bge-m3` / **1024** | 装 Ollama（Windows 安装包）+ `ollama pull bge-m3`；应用侧 HTTP `POST /api/embed` | 0（纯本机算力） | ✅ | 强（多语、长文本、MTEB 中文前列，也常被中文 RAG 教程采用） | 需要新增一个"HTTP 调 Ollama"的薄客户端 + 一个 EmbeddingService 抽象 |
| B 本地 Ollama + nomic-embed-text | `nomic-embed-text` / 768 | 同上 | 0 | ✅ | 一般（英文语料为主） | 同上；维度更小、更快 |
| C 云端 API（OpenAI / 通义 / 智谱等） | 如 `text-embedding-3-small` / 1536 | 无需本地进程，只需 key | 按量计费 | ❌（断网/欠费即不可用） | 强 | 需要 key 管理（**严禁写进仓库**）、超时/重试/降级、可能的网络代理问题 |
| D Spring AI 内置 `TransformersEmbeddingModel`（ONNX `all-MiniLM-L6-v2`） | 384 | 纯 Java 进程内（首次自动下载模型到本地） | 0 | ✅（下载后） | **偏弱**（英文语料训练，中文语义区分度低） | 依赖 Spring AI（见 §4.3 版本问题），但不需外部进程 |

### 4.2 推荐：A（Ollama + bge-m3）

理由（按权重排序）：

1. **不依赖网络与外部账号**：答辩现场断网也能演示（这对毕设至关重要）。
2. **中文效果好**：校园二手的 query 是中文长句（"九成新的高数教材 30 元以内"），中文语义质量直接决定 demo 观感。
3. **可解释、可复现**：本地模型 + 固定版本 + 固定 dims → 论文里可以写清"模型、维度、相似度度量、top-k、融合算法"，别人能复现。
4. **零成本**：不产生 API 费用（毕设常见顾虑）。
5. 备选 D 作为"机器装不了 Ollama"的降级路径（384 维，质量降级但流程完整，仍能演示"语义检索"这件事）。

**对照实验建议（论文加分项）**：把 C（云端 API）作为**离线评测的对照组**，用同一套标注 query 比较
"BM25 / bge-m3 / 云 API" 三种召回的 Recall@10 与 MRR，写成一张表 —— 这是 §7 里 5.6.5 的一部分。

### 4.3 与 Spring AI 的关系（重要取舍）

原计划写的是"Spring AI + ES 8.x + BGE-M3"。本设计建议**本阶段不引入 Spring AI**，改为：

```
Embedding：Spring 自带 RestClient → Ollama  HTTP /api/embed        （零新增依赖*）
检索：    co.elastic.clients:elasticsearch-java（ES 官方 Java Client）→ ES HTTP
```

⚠️ Ollama 的 embedding 接口有两个：**当前版本用 `POST /api/embed`**（入参 `{"model":..., "input":...}`，
支持批量、返回 `embeddings` 数组）；老版本是 `POST /api/embeddings`（入参 `prompt`，返回单个 `embedding`）。
5.6.2 接线时**按本机 `curl.exe -s http://127.0.0.1:11434/api/version` 的版本决定**用哪个，并把选定的接口写进配置项注释。

\* 严格说 ES Java Client 是新增依赖，但它与 Spring Boot 版本**无耦合**（官方独立发版），风险远小于引入 Spring AI。

**为什么不直接上 Spring AI**：
- Spring AI 的 starter 与本项目 Spring Boot **3.2.5** 的版本对应关系**需要在引入前逐项核对**
  （官方 Getting Started 的 dependency-management 一节给出每个版本的 Boot 基线：
  [Spring AI Getting Started](https://spring-ai.spring-doc.cn/spring-ai/1.0.0-M6/getting-started.en.html#dependency-management)）。
  若它要求的 Boot 版本高于 3.2.5，就得**升级 Spring Boot** —— 那属于"换地基"级别的变更，
  会牵动 MyBatis-Plus / ShedLock / springdoc / knife4j 等一串依赖，**风险与收益不成正比**。
- 我们真正需要的只有两件事：**"文本 → 向量"** 与 **"向量 + 关键词 → 排序"**。
  前者是一个 `POST /api/embed`，后者由 ES 自己做 —— 用现成 HTTP 客户端能写得**更薄、更好测**（mock 一个 HTTP 接口 vs mock 一整套 AI 抽象）。
- 反过来说，**如果论文叙述里"用了 Spring AI"是硬性要求**（例如开题报告已写死），
  那就把它作为待拍板项 1 明确提出来：可以走"升级 Boot 到 Spring AI 基线的版本 + 引入 spring-ai-ollama-starter"，
  但需要单独一批做（含全量回归）。

### 4.4 向量输入文本模板（决定了 embedding 质量）

```text
{title}｜{description}｜{分类名}｜{成色文案}｜{交易方式文案}{｜面交：{tradeLocation}}
```

- 用 `｜`（全角竖线）分隔：避免与标题里可能出现的半角符号混淆；也便于人工核对。
- **中文文案而不是数字**（"几乎全新"而不是 `2`）：向量模型是在自然语言上训练的，`2` 对它是噪声。
- `embeddingText` **原样存进 ES**（§3.2）→ 换模板时可精确找出需要重算的文档。
- 长度：`title(≤100) + description(≤5000)` 可能超模型上下文（bge-m3 支持长文本，但仍建议**截断到 512 字符**并把"截断长度"写进配置，避免"同一商品两次 embedding 结果不同"）。

### 4.5 维度与索引的绑定（换模型的代价）

| 场景 | 需要做什么 |
| :--- | :--- |
| 同模型换版本（如 bge-m3 升级） | 全量重算向量 + `_bulk` 覆盖（mapping 不变） |
| 换模型且维度不同（1024 → 768/384/1536） | **必须建新索引**（`dims` 不可改）+ 全量灌数据 + 切别名 |
| 换模型但维度相同 | 全量重算向量即可（仍建议新索引，避免新旧向量混在一个 HNSW 图里） |

→ 这也是 §3.2 里 `embeddingModel` 字段存在的意义：**先查 `embeddingModel != 目标模型` 的文档，再决定重算范围**。

---

## 5. 同步与一致性（MySQL ↔ ES）

### 5.1 写路径双写点清单（5.6.3 要改的地方）

沿用项目既有范式 —— `ProductCacheService` 的"**先更新 DB，再失效缓存**"；ES 同理，但**顺序相反**（见下）：

| 业务动作 | 现有缓存处理 | ES 侧应做 |
| :--- | :--- | :--- |
| 发布商品（→ status=3 待审核） | 删详情缓存 | **不写索引**（待审核不进 AI 检索） |
| 审核通过（3→1） | 删详情缓存 | **写/更新文档**（这是"进入可检索"的唯一入口） |
| 审核不通过（3→0）/ 强制下架 / 卖家下架 | 删详情缓存 | **删除文档** |
| 编辑商品（关键字段变更 → 回 3） | 删详情缓存 | 关键字段变更 → 删文档；仅非关键字段变更 → **更新文档** |
| 删除商品（逻辑删除） | 删详情缓存 | 删除文档 |
| CAS 扣减售罄（status=2） | 删详情缓存 | **删除文档**（`status=2` 不参与检索；若拍板"售罄也展示"，则改为更新 `status`） |
| 库存回补重新上架（2→1） | 删详情缓存 | 写/更新文档 |
| 封禁卖家批量下架（1/2→0） | 删详情缓存 | 删除文档 |

**顺序取舍**：DB 提交后**再**写 ES，且写 ES 失败**不回滚 DB**（只 `log.error` + 打点 + 交给每日重建兜底）。
理由与 `StockService` 的幂等凭证同一取舍：**ES 是派生数据，MySQL 是权威**；把 ES 放进事务会引入跨系统事务问题（要 2PC 或 outbox），毕设量级不值得。

### 5.2 每日全量重建（兜底）

- 复用 `ScheduledTasks` + ShedLock 的既有形态：`@Scheduled(cron = "0 0 3 * * ?")` + `@SchedulerLock(name = "rebuildProductIndexTask", lockAtMostFor = "${app.task.reindex.lock-at-most-for:PT30M}")`。
- 流程：建 `campus_product_v1`（若 v2 场景则建 v2）→ 分批 `SELECT ... WHERE status=1 AND is_deleted=0`（每批 500）+ 调 Ollama 批量 embed → `_bulk` → 切别名 → 删旧索引。
- 思考点：**embedding 是全量重建里唯一昂贵的一步**（5000 条 × 每次 HTTP）。优化：只对 `update_time > 上次重建时间` 或 `embeddingModel != 目标模型` 的商品重算向量，其余复用旧向量（用 `_reindex` 或"按 id 取旧向量"）。

### 5.3 降级链（必须与 5.4.4 同构）

```
AI 检索请求
  ├─ ES 可用 → 双路召回 + RRF
  ├─ ES 不可用 / 超时（沿用 search.circuit-breaker 的 500ms + 熔断）→ MySQL FULLTEXT(ngram)（复用 ProductServiceImpl 的路径）
  └─ FULLTEXT 也失败 → LIKE（现有 doKeywordSearch 的降级）
```

要点：
- **对外响应结构完全不变**（仍是 `PageResult<ProductListVO>`），只是在 `log.warn` 里记"降级了"。前端无需感知。
- 缓存键要区分模式（`ai:search:rag:{hash}` vs `ai:search:kw:{hash}`），否则降级期间写进去的关键词结果会被当成 RAG 结果命中（**这类"降级污染缓存"是本设计里最容易踩的坑**）。
- `RedisKeys.AI_SEARCH_PREFIX`（`ai:search:`）终于会被用上 —— 键格式建议 `ai:search:{mode}:{queryHash}:{filtersHash}`，TTL 60 秒 + 0~10 秒抖动（与 `search.cache.*` 同构）。

### 5.4 一致性验收（5.6.3 的测试点）

- 发布 → 审核通过 → 立刻可被 AI 检索到（≤ 2 秒，含 refresh_interval）。
- 下架/删除 → 立刻搜不到。
- 改标题/描述 → 搜新词能命中、搜旧词不再因为"向量还是旧的"而排前面（**这条最容易漏**：只更新 `title` 不重算 `titleVector` 会导致语义检索仍按旧文本）。
- 关掉 ES → 接口仍可用（降级到 FULLTEXT），且日志有明确 warn。

---

## 6. RAG 生成层设计（检索之外的"G"）

### 6.1 分工

| 层 | 负责 | 技术 |
| :--- | :--- | :--- |
| Retrieval | 找到最相关的 top-k 商品 | ES（BM25 ⊕ kNN + RRF）——**已有设计，确定性、可评测** |
| Generation | 把 query + top-k 商品组合成自然语言"推荐理由 / 筛选建议" | LLM（本地 Ollama 生成模型如 `qwen2.5:7b`，或云 API）——**可选、可降级** |

**关键工程判断**：**生成层必须是"可失败的附加项"**。
检索结果本身已经能回答"给我 30 元以内的高数教材"；生成层只是加一段解释。
所以：生成超时/失败 → 返回 `aiSummary = null` + 正常的检索列表，**绝不能让整条链路失败**。

### 6.2 契约演进（待拍板项 2）

现状：`data: PageResult<ProductListVO>`。要放"推荐理由/模式标记"，两条路：

| 方案 | 形状 | 影响 |
| :--- | :--- | :--- |
| 甲：保持 `PageResult`，`aiSummary` 放到 HTTP header / 另一个接口 | 不改契约 | 前端要多请求一次；语义别扭 |
| **乙：改信封**（推荐） | `data: { mode: "rag"|"keyword", aiSummary: string|null, list: PageResult<ProductListVO> }` | 前端**目前零调用**，趁现在定型代价最小（改的是未使用接口） |

推荐乙，并把 `mode` 暴露出来（便于前端在降级时给"当前为基础检索"的提示，也便于论文里展示"降级路径真实可用"）。

### 6.3 Prompt 与安全边界

```
系统：你是校园二手交易平台的导购助手。只能依据给定的商品列表回答，
      不得编造商品、不得修改价格、不得承诺库存与交易安全；
      若给定列表为空，直接说"没有找到符合条件的商品"。
用户：query + top-k 商品（id / 标题 / 价格 / 成色 / 交易方式 / 地点）
输出：JSON {"summary": "≤120 字推荐理由", "tips": ["筛选建议 1", "..."]}
```

- **不把卖家敏感字段（phone/email）放进 prompt**（与 `ProductListVO` 的既有约定一致）。
- 输出走 JSON schema 校验；解析失败 → 当生成失败处理（`aiSummary=null`）。
- 提示注入：query 是用户可控文本，**不能**把它拼进"系统指令"位置；商品侧文本也视为不可信内容。

### 6.4 超时与缓存

| 层 | 超时 | 缓存 |
| :--- | :--- | :--- |
| Embedding（query → 向量） | 300~500ms（本地 Ollama 通常 20~80ms） | 可缓存 query→向量（`ai:embed:{queryHash}`，TTL 1 小时） |
| 检索（ES） | 500ms（沿用 `search.circuit-breaker.timeout-ms`） | 60s + 抖动 |
| 生成（LLM） | **3~5 秒（单独配置，不复用 500ms）** | 不建议缓存（同 query 结果本就稳定；缓存会掩盖"模型是否真的在工作"） |

---

## 7. 分批判定（5.6.1 ~ 5.6.5）

| 批 | 范围 | 验收标准 |
| :--- | :--- | :--- |
| **5.6.1（本批）** | 审计 + ES 索引设计 + Embedding 对比 + 用户操作清单 + 待拍板 | 本文档 + 用户清单 + 拍板项；**不改任何代码/配置/依赖** |
| 5.6.2 依赖与配置接入 | 按拍板结果引入依赖（ES Java Client + `RestClient` 薄客户端）；新增 `app.ai.*` 配置（开关、ES 地址、模型名、超时、top-k、维度）；健康检查（ES/Ollama 探活）；索引初始化脚本 + mapping 文件；**`/ai/search` 行为保持不变** | 未装 ES/Ollama 时应用仍能正常启动（开关关闭 → 老路径）；装好并打开开关后能写入/查询一条测试文档 |
| 5.6.3 写入与同步 | §5.1 的双写点全部接通；每日全量重建任务；降级链（ES 挂 → FULLTEXT → LIKE）；一致性验收（§5.4 四条） | 发布→审核→可检索（≤2s）；下架/删除即不可检索；关掉 ES 接口仍可用 |
| 5.6.4 检索链路 | 双路召回 + RRF + pre-filter；`/ai/search` 替换为 RAG 检索（生成层先留空）；缓存/熔断接线（键区分 mode）；query 解析（价格/分类/成色词） | 语义检索效果可用（"高数教材"能召回"考研数学复习全书"）；P95 ≤ 500ms（不含生成）；降级与缓存键隔离有测试 |
| 5.6.5 生成 + 前端 + 评测 | RAG 生成层（prompt/schema/降级）；前端智能搜索页（复用列表组件 + `mode` 提示）；离线评测脚本（Recall@10 / MRR：BM25 vs bge-m3 vs 云 API）；文档与论文素材 | 生成失败不影响列表；评测表出数；前端可演示"自然语言搜商品 + 推荐理由" |

---

## 8. 风险与应对

| # | 风险 | 影响 | 应对 |
| :--- | :--- | :--- | :--- |
| 1 | **IK 插件版本必须与 ES 完全一致**，装错版本 ES 直接起不来 | 现场起不来最致命 | ① 下载前核对版本号；② 保留 `standard`/`ngram` 降级 mapping（§3.2 注）；③ 把"装不上插件也要能演示"写进验收 |
| 2 | ES 需要 JVM 堆 + 磁盘（单节点建议 ≥2GB 堆、≥5GB 磁盘）；Ollama 模型约 1~2GB | 磁盘/内存不足会中途失败 | 装前先确认空间（§9.1）；ES 用 `-Xms1g -Xmx1g`（数据量小足够） |
| 3 | 本机 **Docker / WSL 都不可用** | 不能走容器化一键起 ES | 用官方 Windows zip（ES 自带 JDK）；文档里给出"命令行启动 + curl 探活"的固定步骤 |
| 4 | **Spring AI 与 Boot 3.2.5 的版本矩阵**（§4.3） | 引入即可能要升级 Boot，牵动一串依赖 | 本设计走"不引入 Spring AI"；若必须引入，单独开批做升级 + 全量回归 |
| 5 | 双写不一致（ES 有、MySQL 无，或反之） | AI 搜到已下架/删除商品 | DB 提交后写 ES + 失败只告警 + 每日重建兜底 + 检索时**回表**（回表结果为空则丢弃该条，天然过滤） |
| 6 | **向量与文本不同步**（改了标题没重算向量） | 语义检索按旧内容排序 | 写路径里"标题/描述/分类/成色/地点变更"必须触发重算（写进 5.6.3 的一致性用例） |
| 7 | 生成层把无关内容"编"进推荐理由 | 演示时可信度崩塌 | prompt 硬约束 + JSON schema + 只允许引用检索结果；生成失败即降级为空摘要 |
| 8 | 缓存键不区分 mode，降级结果被当 RAG 结果缓存 | 用户看到的解释与结果不匹配、且难以排查 | 键里带 `mode`（§5.3），并加单测 |
| 9 | 评测缺"标注集" | 论文里没有任何量化对比 | 5.6.5 用 20~30 条真实校园 query 手工标注 top-3 相关商品，算 Recall@10/MRR（量小但足够支撑"对比表"） |
| 10 | 内存/CPU 与现有 MySQL/Redis/后端抢资源 | 演示卡顿 | ES 堆限 1g、Ollama 用 7B 量化模型（如 `qwen2.5:7b-instruct-q4_K_M`）；embedding 模型常驻但很小 |

---

## 9. 用户操作清单（需要你手动做的）

> 下面是**必须由你（人）执行**的步骤：装外装、拉模型、确认空间。每步都给了验证命令与期望输出；
> 任何一步失败都**不要继续往下做**（先解决它），并在下一批开始前把结果告诉我。

### 9.1 先确认资源（不装任何东西）

```powershell
# 磁盘可用空间（ES 预算 5GB + 模型 2GB + 余量）
Get-PSDrive C | Select-Object Name, @{n='FreeGB';e={[math]::Round($_.Free/1GB,1)}}

# 内存（ES 1GB 堆 + Ollama 模型常驻 2~6GB，建议总内存 ≥16GB）
Get-CimInstance Win32_ComputerSystem | Select-Object @{n='TotalGB';e={[math]::Round($_.TotalPhysicalMemory/1GB,1)}}
```

**期望**：C 盘可用 ≥ 10GB。若不足 → 先反馈（可改到 D 盘安装，或改用 §4.1 的 D 方案规避 Ollama）。

### 9.2 安装 Elasticsearch 8.x（Windows 原生 zip）

1. 下载与目标版本一致的 Windows zip（**记住版本号，下面装 IK 要用同一个**），解压到 `D:\es\elasticsearch-<版本>`（不要放在项目目录里）。
2. 首次启动前编辑 `config\elasticsearch.yml`，追加（本项目单机单节点）：
   ```yaml
   cluster.name: campus-market
   node.name: campus-es
   discovery.type: single-node
   xpack.security.enabled: false      # 本地演示用；数据不敏感且只监听 127.0.0.1
   network.host: 127.0.0.1
   ```
3. 编辑 `config\jvm.options.d\heap.options`（或 `config\jvm.options`）设堆：`-Xms1g` / `-Xmx1g`。
4. 启动：`D:\es\elasticsearch-<版本>\bin\elasticsearch.bat`
5. **验证**：
   ```powershell
   curl.exe -s http://127.0.0.1:9200
   ```
   **期望**：返回 JSON，含 `"cluster_name" : "campus-market"`、`"version" : { "number" : "8.x.x" }`、`"tagline" : "You Know, for Search"`。
   > ⚠️ **把 `version.number` 记下来发我** —— 5.6.2 的 ES 客户端依赖版本要与它对齐。

### 9.3 安装 IK 中文分词插件（与 ES **完全同版本**）

1. 从 IK 的 release 页下载 `<版本号>` 对应的 zip（版本不一致会导致 ES 启动失败）。
2. 解压到 `D:\es\elasticsearch-<版本>\plugins\ik\`，**保证该目录下直接是 `plugin-descriptor.properties`**（不要多套一层目录）。
3. 重启 ES，**验证**：
   ```powershell
   curl.exe -s -H "Content-Type: application/json" -XPOST "http://127.0.0.1:9200/_analyze" -d "{\"analyzer\":\"ik_max_word\",\"text\":\"考研数学复习全书\"}"
   ```
   **期望**：返回多个中文词条（`考研`/`数学`/`复习`/`全书` 等）。若报 `analyzer [ik_max_word] not found` → 插件没装上，回第 1 步。

### 9.4 安装 Ollama 并拉模型

1. 安装 Ollama Windows 版（官方安装包），安装后确认托盘/服务在跑。
2. 拉模型（两个：一个 embedding、一个生成；生成模型可按 §10 拍板结果换）：
   ```powershell
   ollama pull bge-m3
   ollama pull qwen2.5:7b
   ```
3. **验证**：
   ```powershell
   ollama list
   curl.exe -s http://127.0.0.1:11434/api/tags
   ```
   **期望**：`ollama list` 里有 `bge-m3` 与 `qwen2.5:7b`（或你选的生成模型）。
4. **验证维度（关键）**：
   ```powershell
   curl.exe -s http://127.0.0.1:11434/api/embed -d "{\"model\":\"bge-m3\",\"input\":\"九成新的高等数学教材\"}"
   ```
   **期望**：返回 JSON 里有 `embeddings` 数组。**请把它的长度（向量维度）发我**（预期 1024）——
   这个数字会写进 ES mapping 的 `dims`，**写错就得重建索引**。

### 9.5 把结果反馈给我（下一批的输入）

请回填这四项，5.6.2 才能动手：

| # | 要回填的值 | 从哪来 |
| :--- | :--- | :--- |
| 1 | ES 版本号（如 `8.15.3`） | §9.2 的 `curl.exe -s http://127.0.0.1:9200` |
| 2 | IK 是否装好（`_analyze` 是否返回中文词条） | §9.3 |
| 3 | `bge-m3` 的向量维度（预期 1024） | §9.4 第 4 步 |
| 4 | 生成模型名（默认 `qwen2.5:7b`） | §9.4 / §10 待拍板项 3 |

---

## 10. 待拍板项（需要你决策）

| # | 事项 | 选项 | 我的推荐 | 影响面 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | **是否引入 Spring AI** | 甲 引入（可能需升级 Boot 并全量回归）／乙 不引入（ES Java Client + RestClient 直连 Ollama） | **乙** | 论文叙述、依赖面、风险 |
| 2 | **接口契约是否改信封** | 甲 保持 `PageResult`／乙 改成 `{mode, aiSummary, list}` | **乙**（前端零调用，现在改代价最小） | 前端、API_SPEC、论文"接口设计" |
| 3 | **生成模型** | 甲 `qwen2.5:7b`（约 4.7GB，中文好）／乙 `qwen2.5:3b`（更小更快）／丙 云 API | **甲**（若内存紧张则乙） | 演示流畅度、磁盘/内存 |
| 4 | **是否允许新增依赖** | 甲 允许（ES Java Client + 可选 Ollama 客户端）／乙 只允许 ES Java Client | **甲** | 项目"禁止擅自加依赖"约定需要你放行 |
| 5 | **售罄（status=2）商品是否进索引** | 甲 不进（只索引 status=1）／乙 进索引但排序靠后 | **甲**（与现有列表口径一致；"售罄不该出现在推荐里"） | 检索口径、用户体验 |
| 6 | **检索是否要"个性化"**（论文标题里有"推荐"） | 甲 本阶段只做"语义检索"（推荐=检索结果的排序）／乙 加入用户历史（收藏/订单）做个性化重排 | **甲**（先把 RAG 主链路做扎实，个性化放到 5.6.5 之后或单开一批） | 工作量、论文结构 |
| 7 | **ES 与 Ollama 的安装位置** | 甲 C 盘默认／乙 D 盘（`D:\es`、模型在 Ollama 默认目录） | **乙**（避免挤占系统盘） | 环境准备 |
| 8 | **是否离线评测对照"云 API"** | 甲 做（多一组实验数据）／乙 不做（省一次 key 申请） | **甲**（论文里"三路对比"更有说服力） | 论文实验章节 |

---

## 11. 本批对现有文档的影响（文档同步）

| 文档 | 本批是否改动 | 说明 |
| :--- | :--- | :--- |
| `docs/ai-rag-design.md` | **新增** | 本文档（5.6.x 的设计基线） |
| `PROJECT_CONTEXT.md` | 改动 | 第 4 章补"实施基线指向本文档"；头部追加版本行（设计基线变更） |
| `README.md` / `docs/API_INTERFACE_SPEC.md` | 仅版本号 | 按项目"版本号三处一致"的约定同步 |
| `系统测试.txt` | 追加 | 本批是"审计批"，记录环境实测结果与"未改代码"的说明 |
| `docs/自审报告-2026-09-19.md` | 追加一行 | F1（RAG 未实现）→ 标注"5.6.1 设计完成，实施排期见 design 文档" |
| `api-tests.http` | 不改 | 本批无接口变更（`/ai/search` 契约未动） |
| `SYSTEM_PROMPT.md` | 不改 | 本批没有产生新的"可复用工程约定"（ES/Ollama 的约定会在 5.6.2 落地时再写） |
