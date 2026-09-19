package com.campus.market.common.constant;

/**
 * 敏感密钥生成命令提示（批次 6.0.2 · 任务 E）。
 *
 * <p><b>为什么要收口</b>：JWT 密钥与支付回调密钥的启动报错文案都要告诉运维
 * "怎么生成一个合规的密钥"，而项目此前只给了 Linux/macOS 的 {@code openssl} 命令 ——
 * 本项目的开发与答辩环境是 <b>Windows</b>（见 SYSTEM_PROMPT 的构建环境约束），
 * 只给 openssl 等于让人先去装一个 openssl。两处文案各写一份迟早漂移，
 * 故与 {@link PathConstants} / {@link RedisKeys} 一致，统一收口为常量。</p>
 *
 * <p>使用 {@code \n} 而非 {@code System.lineSeparator()}：报错文案会被日志与测试断言读取，
 * 固定换行符可让断言在任何平台上表现一致。</p>
 */
public final class SecretGenerationHints {

    private SecretGenerationHints() {
    }

    /**
     * 生成 ≥ 32 字节随机密钥的命令（两种平台各一行）。
     *
     * <p>注意：PowerShell 那行用 {@code Get-Random} 生成的是<b>伪随机</b>序列，
     * 适用于开发/演示；生产环境若条件允许，优先用 openssl（CSPRNG）。
     * 见批次报告「六、下一批建议」。</p>
     */
    public static final String KEY_GENERATION_COMMANDS =
            "Linux/macOS: openssl rand -base64 48\n"
                    + "Windows PowerShell: [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))";
}
