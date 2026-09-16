package com.campus.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 文件上传结果 VO。
 */
@Data
@Schema(description = "文件上传结果")
public class UploadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "可直接访问的图片 URL（用于商品发布 / 编辑的 imageUrls）")
    private String url;
}
