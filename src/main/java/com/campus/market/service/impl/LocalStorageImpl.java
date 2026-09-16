package com.campus.market.service.impl;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.StorageProperties;
import com.campus.market.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘存储实现（默认实现，毕设演示推荐）。
 *
 * <h3>上传校验链（顺序执行，任一失败即拒绝）</h3>
 * <ol>
 *   <li>登录校验：userId 为空或 ≤ 0 → 401；</li>
 *   <li>大小校验：≤ 5MB；</li>
 *   <li>声明类型校验：扩展名 / Content-Type 白名单 jpg/jpeg/png/webp；</li>
 *   <li><b>文件魔数校验</b>（只信任文件内容，不信任 {@code getContentType()}）：
 *       JPEG {@code FF D8 FF}、PNG {@code 89 50 4E 47 0D 0A 1A 0A}、WebP {@code RIFF....WEBP}；</li>
 *   <li>像素校验：最大边长 ≤ 8192px、总像素 ≤ 5000 万；</li>
 *   <li>落盘：UUID 重命名（<b>严禁使用用户原始文件名</b>），按 {@code product/{userId}/{uuid}.jpg} 分目录，
 *       并生成 400x400 等比压缩缩略图 {@code product/{userId}/{uuid}_thumb.jpg}。</li>
 * </ol>
 *
 * <p>安全：拼接后的规范化路径必须仍位于 basePath 内，否则判定为路径穿越并拒绝（403）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalStorageImpl implements StorageService {

    /** 存储实现类型标识。 */
    private static final String TYPE = "local";

    /** 商品图片目录前缀。 */
    private static final String PRODUCT_DIR = "product";

    /** 单文件大小上限：5MB。 */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /** 最大边长（像素）。 */
    private static final int MAX_EDGE_PX = 8192;

    /** 总像素上限：5000 万。 */
    private static final long MAX_TOTAL_PIXELS = 50_000_000L;

    /** 缩略图边长（等比缩放，不放大）。 */
    private static final int THUMBNAIL_SIZE = 400;

    /** 图片扩展名白名单。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    /** Content-Type 白名单（仅作辅助校验，最终以魔数为准）。 */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    private final StorageProperties storageProperties;

    @Override
    public String upload(MultipartFile file, Long userId) {
        // ① 登录校验：未登录 / 非法 userId 直接拒绝
        if (userId == null || userId <= 0) {
            throw BusinessException.unauthorized();
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片大小不能超过5MB");
        }

        // ② 声明类型白名单（扩展名 / Content-Type）
        checkDeclaredType(file);

        // ③ 文件魔数校验（只信任文件内容）
        String format = detectImageFormat(file);

        // ④ 尺寸与像素校验
        BufferedImage source = readImage(file);
        checkDimension(source);

        // ⑤ UUID 重命名 + 分目录落盘
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String relativePath = PRODUCT_DIR + "/" + userId + "/" + uuid + ".jpg";
        Path basePath = basePath();
        Path target = resolveSafely(basePath, relativePath);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            log.error("创建上传目录失败: {}", target.getParent(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件上传失败，请稍后重试");
        }
        // IO 流规范：InputStream 必须 try-with-resources 释放
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("图片写入本地磁盘失败: {}", target, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件上传失败，请稍后重试");
        }

        // ⑥ 生成 400x400 压缩缩略图（失败不阻断主流程）
        writeThumbnail(source, basePath, PRODUCT_DIR + "/" + userId + "/" + uuid + "_thumb.jpg");

        log.info("图片上传成功: userId={}, format={}, size={}B, path={}", userId, format, file.getSize(), relativePath);
        return buildUrl(relativePath);
    }

    @Override
    public void delete(String url) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        String relativePath = stripUrlPrefix(url);
        if (!StringUtils.hasText(relativePath)) {
            return;
        }
        Path basePath = basePath();
        Path target;
        try {
            target = resolveSafely(basePath, relativePath);
        } catch (BusinessException e) {
            // 路径穿越 / 非法路径：拒绝删除并记录告警
            log.warn("拒绝删除存储目录之外的文件: url={}", url);
            return;
        }
        deleteQuietly(target);
        deleteQuietly(thumbnailPathOf(target));
    }

    @Override
    public String getType() {
        return TYPE;
    }

    // ------------------------------------------------------------ 校验

    /**
     * 声明类型校验：扩展名必须在白名单内；Content-Type 存在时也必须在白名单内。
     * 该步骤只是快速失败，最终仍然以魔数为准。
     */
    private void checkDeclaredType(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 jpg/jpeg/png/webp 格式图片");
        }
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)
                && !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 jpg/jpeg/png/webp 格式图片");
        }
    }

    /**
     * 文件魔数校验（只读取文件头，不信任客户端声明的 Content-Type）。
     *
     * @return 识别出的真实格式：jpeg / png / webp
     */
    private String detectImageFormat(MultipartFile file) {
        byte[] header = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(header, 0, header.length);
        } catch (IOException e) {
            log.warn("读取图片魔数失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片文件读取失败");
        }
        // JPEG: FF D8 FF
        if (read >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (read >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 'P'
                && header[2] == 'N'
                && header[3] == 'G'
                && (header[4] & 0xFF) == 0x0D
                && (header[5] & 0xFF) == 0x0A
                && (header[6] & 0xFF) == 0x1A
                && (header[7] & 0xFF) == 0x0A) {
            return "png";
        }
        // WebP: "RIFF" + 4 字节长度 + "WEBP"
        if (read >= 12
                && header[0] == 'R'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == 'F'
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P') {
            return "webp";
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "图片文件内容不合法（魔数校验失败）");
    }

    private BufferedImage readImage(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "图片文件内容不合法，无法解析");
            }
            return image;
        } catch (IOException e) {
            log.warn("解析图片尺寸失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片文件内容不合法，无法解析");
        }
    }

    /**
     * 像素校验：最大边长 ≤ 8192px，总像素 ≤ 5000 万。
     */
    private void checkDimension(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width > MAX_EDGE_PX || height > MAX_EDGE_PX) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片最大边长不能超过8192px");
        }
        if ((long) width * height > MAX_TOTAL_PIXELS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片总像素不能超过5000万");
        }
    }

    // ------------------------------------------------------------ 落盘 / 缩略图

    /**
     * 生成 400x400 等比压缩缩略图（Thumbnailator 未引入依赖，使用 ImageIO + Graphics2D 实现）。
     */
    private void writeThumbnail(BufferedImage source, Path basePath, String relativePath) {
        int width = source.getWidth();
        int height = source.getHeight();
        // 等比缩放且不放大
        double scale = Math.min(1.0d, (double) THUMBNAIL_SIZE / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        // 目标图固定 TYPE_INT_RGB：避免 PNG 透明通道导致 JPEG 编码失败
        BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        Path target = resolveSafely(basePath, relativePath);
        try {
            Files.createDirectories(target.getParent());
            ImageIO.write(thumbnail, "jpg", target.toFile());
        } catch (IOException e) {
            // 缩略图失败不影响主图，仅降级记录
            log.warn("生成缩略图失败: path={}, err={}", target, e.getMessage());
        }
    }

    // ------------------------------------------------------------ 路径工具

    private Path basePath() {
        String configured = storageProperties.getLocal().getBasePath();
        String path = StringUtils.hasText(configured) ? configured.trim() : "./uploads";
        return Paths.get(path).toAbsolutePath().normalize();
    }

    /**
     * 路径穿越防护：规范化后必须仍位于 basePath 内，否则拒绝。
     */
    private Path resolveSafely(Path basePath, String relativePath) {
        Path target = basePath.resolve(relativePath).normalize();
        if (!target.startsWith(basePath)) {
            throw BusinessException.forbidden();
        }
        return target;
    }

    private String buildUrl(String relativePath) {
        String prefix = normalizedUrlPrefix();
        return prefix + "/" + relativePath.replace('\\', '/');
    }

    private String normalizedUrlPrefix() {
        String prefix = storageProperties.getLocal().getUrlPrefix();
        String normalized = StringUtils.hasText(prefix) ? prefix.trim().replace('\\', '/') : "";
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 从访问 URL 还原相对路径（去掉 urlPrefix）。
     */
    private String stripUrlPrefix(String url) {
        String relative = url.trim().replace('\\', '/');
        String prefix = normalizedUrlPrefix();
        if (StringUtils.hasText(prefix) && relative.startsWith(prefix)) {
            relative = relative.substring(prefix.length());
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return relative;
    }

    /**
     * 缩略图路径：{@code xxx.jpg → xxx_thumb.jpg}。
     */
    private Path thumbnailPathOf(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String thumbName = dot > 0
                ? fileName.substring(0, dot) + "_thumb" + fileName.substring(dot)
                : fileName + "_thumb";
        return path.resolveSibling(thumbName);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除文件失败: path={}, err={}", path, e.getMessage());
        }
    }

    /**
     * 提取小写扩展名（不含点）；无扩展名返回空串。
     */
    private String extensionOf(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
