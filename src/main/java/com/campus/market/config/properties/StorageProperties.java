package com.campus.market.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储抽象配置：storage.type=local|minio，默认 local（毕设演示推荐）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** 存储实现类型：local | minio。 */
    private String type = "local";

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
