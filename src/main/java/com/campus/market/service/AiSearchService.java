package com.campus.market.service;

import com.campus.market.common.result.PageResult;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.vo.ProductListVO;

/**
 * AI 智能导购（RAG / 语义检索）接口预留。
 *
 * <h3>MVP 阶段（当前实现）</h3>
 * 退化为基础检索：MySQL {@code LIKE CONCAT('%', #{keyword}, '%')} 匹配 {@code title} 或 {@code description}，
 * <b>必须参数化</b>（严禁字符串拼接）；MVP 商品量上限 5000 条，超过必须引入 MySQL FULLTEXT（ngram）
 * 或提前引入 ES；接口需带超时熔断（500ms）与结果缓存（60 秒）。
 *
 * <h3>后期规划（论文"未来工作"）</h3>
 * Spring AI + Elasticsearch 8.x（dense_vector + kNN）+ IK 分词器 + BGE-M3 向量模型，
 * 实现真正的自然语言语义检索与个性化推荐。这是本项目的毕设创新点。
 */
public interface AiSearchService {

    /**
     * 自然语言检索商品。
     *
     * @param query 自然语言查询串（如"九成新的高数教材 30 元以内"）
     * @return 分页商品列表（与普通列表同一 VO，前端可复用）
     */
    PageResult<ProductListVO> search(ProductQuery query);
}
