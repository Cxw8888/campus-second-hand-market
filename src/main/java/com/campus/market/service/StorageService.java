package com.campus.market.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储抽象接口。
 *
 * <p>默认实现为 {@code LocalStorageImpl}（本地磁盘，毕设演示推荐），
 * 通过 {@code app.storage.type=local|minio} 配置切换。
 * <b>MinIO 为可选实现</b>（{@code MinioStorageImpl}）：本项目 pom.xml 未引入 minio 依赖，
 * 如需启用 {@code storage.type=minio}，需自行引入 {@code io.minio:minio} 依赖并实现本接口。</p>
 *
 * <p>实现方必须保证：≤5MB、文件魔数校验、类型白名单 jpg/jpeg/png/webp、
 * 单次 ≤9 张、最大边长 8192px、总像素 ≤5000 万、UUID 重命名分目录、生成 400x400 缩略图。</p>
 */
public interface StorageService {

    /**
     * 上传单个图片文件。
     *
     * @param file   上传文件（multipart）
     * @param userId 当前登录用户ID（用于分目录 {@code product/{userId}/}）
     * @return 可访问的 URL（urlPrefix + 相对路径）
     */
    String upload(MultipartFile file, Long userId);

    /**
     * 删除文件（按上传时返回的 URL；同时清理缩略图）。文件不存在时静默返回。
     */
    void delete(String url);

    /**
     * 当前生效的存储实现类型：local | minio。
     */
    String getType();
}
