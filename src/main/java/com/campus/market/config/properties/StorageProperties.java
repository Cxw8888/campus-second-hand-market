package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储抽象配置：storage.type=local|minio，默认 local（毕设演示推荐）。
 *
 * <p><b>批次 6.0.5.2 · M6-A2 起</b>新增按用户的上传配额（文件数 + 总字节），
 * 由 {@code StorageQuotaService} 用 Redis 计数，详见 {@link #maxFilesPerUser} /
 * {@link #maxTotalSizeMbPerUser}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** 存储实现类型：local | minio。 */
    private String type = "local";

    /**
     * 单用户文件数上限（默认 100）。
     *
     * <p>校园场景下单个学生上传 100 张商品图（约 50MB）足够覆盖正常使用；
     * 没有配额时任一登录用户都能把磁盘打满（自审报告 M6 的 DoS 面）。</p>
     */
    private int maxFilesPerUser = 100;

    /**
     * 单用户总容量上限（MB，默认 50）。
     *
     * <p>与 {@link #maxFilesPerUser} 是"或"关系：任一超限即拒绝。</p>
     */
    private long maxTotalSizeMbPerUser = 50L;

    private Local local = new Local();

    private Minio minio = new Minio();

    @Data
    public static class Local {
        /** 本地磁盘根目录。 */
        private String basePath = "./uploads";
        /** 对外访问 URL 前缀。 */
        private String urlPrefix = "/static/uploads";
    }

    @Data
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket = "campus-market";
    }
}
