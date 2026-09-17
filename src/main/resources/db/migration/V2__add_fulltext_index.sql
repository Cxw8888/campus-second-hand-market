-- =====================================================================
-- V2: 商品关键词检索升级为 MySQL FULLTEXT（ngram 解析器）
-- =====================================================================
-- 背景（PROJECT_CONTEXT 第 4 章 · AI 扩展预留）：
--   MVP 阶段关键词检索用 MySQL LIKE，商品量上限 5000 条；超过该量级必须引入
--   MySQL FULLTEXT（ngram 解析器）或提前引入 ES。本迁移把 LIKE 升级为 FULLTEXT 索引，
--   应用层保留 LIKE 作为自动降级路径（见 ProductServiceImpl.doKeywordSearch）。
--
-- 为什么必须带 WITH PARSER ngram：
--   默认的全文解析器按"空格/标点"切词，中文整句会被当成一个 token，
--   于是「考研数学」搜不到「考研数学复习全书」。ngram 解析器按 N 元组切分（本机
--   ngram_token_size=2，即按 2 字一组），中文才能被正常检索。
--
-- 为什么是 (title, description) 两列联合索引：
--   与既有 LIKE 路径的语义保持一致 —— 原实现就是 title LIKE %kw% OR description LIKE %kw%。
--   注意：MATCH() 的列清单必须与索引定义**完全一致**，多一列少一列都用不上索引。
--
-- 影响评估：
--   · 只新增索引，不 DROP、不改列、不动任何既有索引（idx_category_status_price /
--     idx_user_id / idx_product_title 全部保留）；
--   · MySQL 8.0.46 + ngram_token_size=2（默认值，本迁移不修改该参数）；
--   · 加索引会重建表。本项目商品量上限 5000 条（第 4 章），属秒级、可忽略；
--     若将来数据量真到了十万级，这条 ALTER 需要走在线 DDL 窗口。
-- =====================================================================

ALTER TABLE tb_product
    ADD FULLTEXT INDEX ft_product_title_desc (title, description)
    WITH PARSER ngram;
