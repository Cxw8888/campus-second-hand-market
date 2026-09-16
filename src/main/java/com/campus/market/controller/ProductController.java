package com.campus.market.controller;

import com.campus.market.common.result.PageResult;
import com.campus.market.common.result.Result;
import com.campus.market.dto.product.ProductQuery;
import com.campus.market.dto.product.ProductSaveRequest;
import com.campus.market.service.ProductService;
import com.campus.market.vo.ProductDetailVO;
import com.campus.market.vo.ProductListVO;
import com.campus.market.vo.ProductSaveVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口。
 *
 * <p>路径语义：{@code /list} 与 {@code /detail/**} 为<b>可选认证</b>（游客可浏览，登录后可见性提升）；
 * 其余为<b>强制认证</b>。业务接口一律 HTTP 200，仅未登录 / Token 失效返回 HTTP 401。</p>
 */
@Tag(name = "商品", description = "商品检索、详情、发布、编辑、删除、下架")
@RestController
@RequestMapping("/api/v1/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 商品列表（游客 / 买家视角：仅上架中商品，排序下推 SQL）。
     */
    @Operation(summary = "商品列表（可选认证，仅返回 status=1 上架中商品）")
    @GetMapping("/list")
    public Result<PageResult<ProductListVO>> list(@Valid ProductQuery query) {
        return Result.success(productService.list(query));
    }

    /**
     * 商品详情（可见性分级，不满足 → code=204）。
     */
    @Operation(summary = "商品详情（可选认证，可见性分级：游客仅上架中，卖家本人/管理员全部状态）")
    @GetMapping("/detail/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(productService.detail(id));
    }

    /**
     * 发布商品（落库 status=3 待审核）。
     */
    @Operation(summary = "发布商品（强制认证，落库 status=3 待审核）")
    @PostMapping
    public Result<ProductSaveVO> create(@Valid @RequestBody ProductSaveRequest request) {
        Long id = productService.create(request);
        ProductSaveVO vo = new ProductSaveVO();
        vo.setId(id);
        return Result.success(vo);
    }

    /**
     * 编辑商品（PUT 全量更新语义）。
     */
    @Operation(summary = "编辑商品（强制认证，全量更新；关键字段变更 → 重新审核 status=3）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest request) {
        productService.update(id, request);
        return Result.success();
    }

    /**
     * 删除商品（存在未完成订单 → code=207）。
     */
    @Operation(summary = "删除商品（强制认证，存在未完成订单 → 207）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success();
    }

    /**
     * 我的商品（卖家视角，含待审核）。
     */
    @Operation(summary = "我的商品（强制认证，卖家视角，含 status=3 待审核）")
    @GetMapping("/my")
    public Result<PageResult<ProductListVO>> listMy(@Valid ProductQuery query) {
        return Result.success(productService.listMy(query));
    }

    /**
     * 下架自己的商品（status=1 → 0）。
     */
    @Operation(summary = "下架商品（强制认证，卖家下架自己的商品 1→0）")
    @PutMapping("/off-shelf/{id}")
    public Result<Void> offShelf(@PathVariable Long id) {
        productService.offShelf(id);
        return Result.success();
    }
}
