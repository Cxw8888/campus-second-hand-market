package com.campus.market.controller;

import com.campus.market.common.result.Result;
import com.campus.market.security.UserContext;
import com.campus.market.service.StorageService;
import com.campus.market.vo.UploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口（强制认证）。
 *
 * <p>约束：≤5MB、魔数校验、白名单 jpg/jpeg/png/webp、最大边长 8192px、总像素 ≤5000 万、
 * UUID 重命名按 {@code product/{userId}/} 分目录，并生成 400x400 缩略图。</p>
 */
@Tag(name = "文件上传", description = "商品图片上传（本地存储 / MinIO 可切换）")
@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class UploadController {

    private final StorageService storageService;

    /**
     * 上传单张商品图片（前端循环调用，最多 9 张）。
     */
    @Operation(summary = "上传商品图片（强制认证，multipart/form-data，字段名 file）")
    @PostMapping("/image")
    public Result<UploadVO> uploadImage(@RequestParam("file") MultipartFile file) {
        Long userId = UserContext.requireUserId();
        String url = storageService.upload(file, userId);
        UploadVO vo = new UploadVO();
        vo.setUrl(url);
        return Result.success(vo);
    }
}
