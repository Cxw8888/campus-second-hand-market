package com.campus.market.controller;

import com.campus.market.common.query.PageQuery;
import com.campus.market.common.result.PageResult;
import com.campus.market.common.result.Result;
import com.campus.market.service.FavoriteService;
import com.campus.market.vo.FavoriteVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 收藏接口（强制认证路径）。
 */
@Tag(name = "收藏", description = "商品收藏 / 取消收藏 / 收藏列表")
@RestController
@RequestMapping("/api/v1/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "收藏商品", description = "唯一键幂等，重复收藏返回成功")
    @PostMapping("/{productId}")
    public Result<Void> add(@PathVariable Long productId) {
        favoriteService.add(productId);
        return Result.success();
    }

    @Operation(summary = "取消收藏", description = "物理删除（tb_favorite 无 is_deleted）")
    @DeleteMapping("/{productId}")
    public Result<Void> remove(@PathVariable Long productId) {
        favoriteService.remove(productId);
        return Result.success();
    }

    @Operation(summary = "我的收藏列表", description = "不过滤已删除/已下架商品，返回 productStatus 与 isDeleted 供前端标注失效")
    @GetMapping("/list")
    public Result<PageResult<FavoriteVO>> list(@Valid PageQuery query) {
        return Result.success(favoriteService.list(query));
    }

    @Operation(summary = "是否已收藏")
    @GetMapping("/check/{productId}")
    public Result<Map<String, Boolean>> check(@PathVariable Long productId) {
        return Result.success(Map.of("favorited", favoriteService.check(productId)));
    }
}
