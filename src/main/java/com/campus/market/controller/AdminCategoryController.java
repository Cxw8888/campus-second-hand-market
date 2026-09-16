package com.campus.market.controller;

import com.campus.market.common.enums.AuditOperationType;
import com.campus.market.common.result.Result;
import com.campus.market.dto.product.CategoryMigrateRequest;
import com.campus.market.dto.product.CategorySaveRequest;
import com.campus.market.security.RequireRole;
import com.campus.market.service.AdminAuditService;
import com.campus.market.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理端分类管理接口（强制认证 + {@code @RequireRole(1)}，普通用户 → 403）。
 *
 * <p>审计日志（CREATE_CATEGORY / UPDATE_CATEGORY / DELETE_CATEGORY）由 {@link AdminAuditService}
 * 与业务在<b>同一事务</b>内写入；审计写入失败时内部降级为 error 日志，业务操作仍提交。</p>
 */
@Tag(name = "管理端-分类管理", description = "分类 CRUD 与级联迁移（仅管理员）")
@RestController
@RequestMapping("/api/v1/admin/category")
@RequireRole(1)
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;
    private final AdminAuditService adminAuditService;

    /**
     * 新建分类。
     */
    @Operation(summary = "新建分类（审计：CREATE_CATEGORY）")
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> create(@Valid @RequestBody CategorySaveRequest request) {
        Long id = categoryService.create(request);
        adminAuditService.record(AuditOperationType.CREATE_CATEGORY, "CATEGORY", id, "新建分类: " + request.getName());
        Map<String, Object> data = new HashMap<>(2);
        data.put("id", id);
        return Result.success(data);
    }

    /**
     * 修改分类。
     */
    @Operation(summary = "修改分类（审计：UPDATE_CATEGORY）")
    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveRequest request) {
        categoryService.update(id, request);
        adminAuditService.record(AuditOperationType.UPDATE_CATEGORY, "CATEGORY", id, "修改分类: " + request.getName());
        return Result.success();
    }

    /**
     * 删除分类（分类下有商品 → code=208）。
     */
    @Operation(summary = "删除分类（分类下有商品 → 208，审计：DELETE_CATEGORY）")
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        adminAuditService.record(AuditOperationType.DELETE_CATEGORY, "CATEGORY", id, "删除分类");
        return Result.success();
    }

    /**
     * 级联迁移：把源分类下的商品迁移到目标分类。
     */
    @Operation(summary = "分类级联迁移商品（返回迁移条数）")
    @PutMapping("/migrate")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> migrate(@Valid @RequestBody CategoryMigrateRequest request) {
        int moved = categoryService.migrate(request);
        adminAuditService.record(AuditOperationType.UPDATE_CATEGORY, "CATEGORY", request.getFromCategoryId(),
                "分类迁移商品到 " + request.getToCategoryId() + ", 迁移条数=" + moved);
        Map<String, Object> data = new HashMap<>(2);
        data.put("movedCount", moved);
        return Result.success(data);
    }
}
