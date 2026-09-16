package com.campus.market.controller;

import com.campus.market.common.result.Result;
import com.campus.market.service.CategoryService;
import com.campus.market.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品分类查询接口（<b>可选认证路径</b>）。
 *
 * <p>有 Token 则解析注入 {@code UserContext}，无 / 失效 Token 静默放行（游客可浏览）。</p>
 */
@Tag(name = "商品分类", description = "分类查询（游客可浏览）")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 分类列表（按 sort 升序）。
     */
    @Operation(summary = "分类列表（可选认证，按 sort 升序）")
    @GetMapping("/category/list")
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.list());
    }
}
