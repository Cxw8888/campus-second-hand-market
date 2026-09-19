package com.campus.market.service.impl;

import com.campus.market.common.exception.BusinessException;
import com.campus.market.config.properties.StorageProperties;
import com.campus.market.service.StorageQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 批次 6.0.5.2 安全加固 · M6-A1/A2 单测：上传的"解压炸弹"防护与配额。
 *
 * <h3>A1：修前先全量解码再校验尺寸 ⇒ 解压炸弹能打爆内存</h3>
 * <p>修前顺序是 {@code ImageIO.read(file)} → {@code checkDimension(image)}：
 * 一个 5MB 的 PNG 只要在 IHDR 里声明 {@code 10000×10000}，解码阶段就要分配约 400MB 像素缓冲区
 * （放大到 40000×40000 就是 6.4GB），直接 {@code OutOfMemoryError} ——
 * 而它是 {@link Error}，全局异常处理器的 {@code Exception} 兜底接不住。
 * 本测试用<b>手工构造的 PNG 头</b>（真实字节只有几十字节、声明尺寸 10000×10000）复现这颗炸弹：
 * 修后应当被"读头部"这一步拦下，并且<b>根本不进入解码</b>。</p>
 *
 * <h3>A2：配额</h3>
 * <p>校验通过才落盘；配额超限时连文件都不写。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalStorageImageGuardTest {

    private static final long USER_ID = 7001L;

    @TempDir
    Path tempDir;

    @Mock
    private StorageQuotaService storageQuotaService;

    private StorageProperties storageProperties;

    private LocalStorageImpl storageService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.getLocal().setBasePath(tempDir.toString());
        storageProperties.getLocal().setUrlPrefix("/static/uploads");
        storageService = new LocalStorageImpl(storageProperties, storageQuotaService);
    }

    /** 真实的小图（200x200 PNG，可正常解码）。 */
    private static byte[] realPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * 解压炸弹：用一张 2x2 的真 PNG，把 IHDR 里声明的宽高改成任意巨大值并修好 CRC。
     *
     * <p>文件真实体积仍是几十字节，但任何"全量解码"的实现都会按声明尺寸分配内存 ——
     * 这正是自审报告 M6 ① 描述的攻击形态。PNG 结构：
     * {@code [0-7] 签名 [8-11] 长度=13 [12-15] "IHDR" [16-28] 数据 [29-32] CRC}，
     * 其中数据的前 8 字节是宽、高（各 4 字节大端），CRC 覆盖 {@code "IHDR" + 数据}（即 12..28）。</p>
     */
    private static byte[] pngBomb(int declaredWidth, int declaredHeight) throws Exception {
        byte[] bytes = realPng(2, 2);
        writeInt(bytes, 16, declaredWidth);
        writeInt(bytes, 20, declaredHeight);
        writeInt(bytes, 29, (int) crc32(bytes, 12, 17));
        return bytes;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    private static MockMultipartFile pngFile(byte[] bytes) {
        return new MockMultipartFile("file", "bomb.png", "image/png", bytes);
    }

    private long uploadedFileCount() throws Exception {
        Path userDir = tempDir.resolve("product").resolve(String.valueOf(USER_ID));
        if (!Files.exists(userDir)) {
            return 0L;
        }
        try (var stream = Files.list(userDir)) {
            return stream.count();
        }
    }

    @Test
    @DisplayName("① 解压炸弹（声明 10000×10000、实际几十字节）→ 读头部即拒绝，不进入解码")
    void decompressionBombShouldBeRejectedByHeaderCheck() throws Exception {
        MockMultipartFile bomb = pngFile(pngBomb(10_000, 10_000));

        assertThatCode(() -> storageService.upload(bomb, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大边长不能超过8192px");

        // 拒绝发生在落盘之前：没有产生任何文件，配额也没被记账
        assertThat(uploadedFileCount()).isZero();
        verify(storageQuotaService, never()).recordUpload(anyLong(), anyLong());
    }

    @Test
    @DisplayName("② 边长合法但总像素超 5000 万（8000×7000）→ 同样在读头部阶段拒绝")
    void oversizedPixelCountShouldBeRejected() throws Exception {
        MockMultipartFile bomb = pngFile(pngBomb(8_000, 7_000));

        assertThatThrownBy(() -> storageService.upload(bomb, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("总像素不能超过5000万");

        assertThat(uploadedFileCount()).isZero();
    }

    @Test
    @DisplayName("③ 正常图片 → 校验通过后落盘、生成缩略图、记配额")
    void normalImageShouldBeStoredAndCounted() throws Exception {
        MockMultipartFile file = pngFile(realPng(200, 200));

        String url = storageService.upload(file, USER_ID);

        assertThat(url).startsWith("/static/uploads/product/" + USER_ID + "/").endsWith(".jpg");
        // 主图 + 缩略图
        assertThat(uploadedFileCount()).isEqualTo(2L);
        verify(storageQuotaService).assertWithinQuota(USER_ID, file.getSize());
        verify(storageQuotaService).recordUpload(USER_ID, file.getSize());
    }

    @Test
    @DisplayName("④ 配额已满 → 在上传前拒绝（连文件都不写，也不记配额）")
    void quotaExceededShouldRejectBeforeWriting() throws Exception {
        MockMultipartFile file = pngFile(realPng(200, 200));
        doThrow(new BusinessException(com.campus.market.common.enums.ErrorCode.PARAM_ERROR, "上传配额已满"))
                .when(storageQuotaService).assertWithinQuota(anyLong(), anyLong());

        assertThatThrownBy(() -> storageService.upload(file, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传配额已满");

        assertThat(uploadedFileCount()).isZero();
        verify(storageQuotaService, never()).recordUpload(anyLong(), anyLong());
    }

    @Test
    @DisplayName("⑤ WebP：魔数能过，但 JDK ImageIO 无解码器 → 「不支持的图片格式」（行为与修前一致）")
    void webpShouldBeRejectedAsUnsupported() {
        byte[] webp = new byte[32];
        System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
        webp[4] = 0x20;
        System.arraycopy("WEBP".getBytes(), 0, webp, 8, 4);
        MockMultipartFile file = new MockMultipartFile("file", "a.webp", "image/webp", webp);

        assertThatThrownBy(() -> storageService.upload(file, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的图片格式");
    }

    @Test
    @DisplayName("⑥ 非图片内容（魔数不通过）→ 拒绝（既有防线回归）")
    void nonImageShouldBeRejectedByMagicNumber() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png",
                "this is definitely not an image".getBytes());

        assertThatThrownBy(() -> storageService.upload(file, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("魔数校验失败");
    }
}
