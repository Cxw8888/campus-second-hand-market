package com.campus.market.controller;

import com.campus.market.common.result.PageResult;
import com.campus.market.common.result.Result;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.service.AiSearchService;
import com.campus.market.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 智能导购接口（可选认证路径）。
 *
 * <p>MVP 阶段为基础关键词检索（title / description 参数化 LIKE），
 * 接口形如 {@code GET /api/v1/ai/search?query=自然语言}，为后期 RAG 语义检索预留契约。</p>
 */
@Tag(name = "AI 智能导购（预留）", description = "基于 RAG 的语义检索与智能推荐，MVP 阶段退化为基础检索")
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiSearchController {

    private final AiSearchService aiSearchService;

    @Operation(summary = "自然语言检索商品（预留接口）",
            description = "MVP: MySQL 参数化 LIKE 检索 title/description；P2: Spring AI + ES kNN + BGE-M3")
    @GetMapping("/search")
    public Result<PageResult<ProductListVO>> search(
            @Parameter(description = "自然语言查询串（契约见 PROJECT_CONTEXT 第 4 章）",
                    example = "九成新的高等数学教材")
            @RequestParam(value = "query", required = false) String query,
            @Valid ProductQuery productQuery) {
        // 契约：GET /api/v1/ai/search?query=自然语言
        //      （PROJECT_CONTEXT 第 4 章「接口预留」/ API_INTERFACE_SPEC 8.1）
        //
        // ⚠️ 必须显式把 ?query= 映射到 ProductQuery.keyword：
        //    ProductQuery 里没有 query 属性，若不做这层映射，Spring 会【静默忽略】?query= 参数，
        //    keyword 保持 null → AiSearchServiceImpl 中
        //    `.and(keyword != null && !keyword.isBlank(), ...)` 整段 LIKE 条件被跳过
        //    → 接口退化成"返回全部上架商品"，且不报任何错误（曾导致验收用例 3.20 拿到无关商品）。
        if (query != null && !query.isBlank()) {
            productQuery.setKeyword(query);
        }
        return Result.success(aiSearchService.search(productQuery));
    }
}
