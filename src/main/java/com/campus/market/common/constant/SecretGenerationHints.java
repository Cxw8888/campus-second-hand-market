package com.campus.market.common.constant;

/**
 * 敏感密钥生成命令提示（批次 6.0.2 · 任务 E，6.0.3 · 任务 E 修正）。
 *
 * <p><b>为什么要收口</b>：JWT 密钥与支付回调密钥的启动报错文案都要告诉运维
 * "怎么生成一个合规的密钥"，而项目此前只给了 Linux/macOS 的 {@code openssl} 命令 ——
 * 本项目的开发与答辩环境是 <b>Windows</b>（见 SYSTEM_PROMPT 的构建环境约束），
 * 只给 openssl 等于让人先去装一个 openssl。两处文案各写一份迟早漂移，
 * 故与 {@link PathConstants} / {@link RedisKeys} 一致，统一收口为常量。</p>
 *
 * <h3>6.0.3 修正：PowerShell 那行必须真的是 CSPRNG</h3>
 * <p>6.0.2 写的是 {@code 1..48 | ForEach-Object { Get-Random -Max 256 }} —— 而
 * {@code Get-Random} 是<b>伪随机</b>（非密码学安全），拿它生成签名密钥属于"看着随机、
 * 实则可预测"。本批改为 {@code RandomNumberGenerator}（CSPRNG）。</p>
 *
 * <p><b>实测坑（本机 Windows PowerShell 5.1 实测）</b>：静态方法
 * {@code RandomNumberGenerator.GetBytes(int)} 是 .NET Core 3.0 / PowerShell 7 才有的 API，
 * 在 {@code powershell.exe}（5.1 / .NET Framework）上会直接报
 * <i>"does not contain a method named 'GetBytes'"</i>。因此文案里给出两行，任选其一 ——
 * 一行给 PowerShell 7+，一行给仍然默认的 5.1（{@code Create().GetBytes($b)} 在两者上都可用）。</p>
 */
public final class SecretGenerationHints {

    private SecretGenerationHints() {
    }

    /**
     * 生成 ≥ 32 字节随机密钥的命令（三行：Linux/macOS、PowerShell 7+、PowerShell 5.1）。
     *
     * <p>使用 {@code \n} 而非 {@code System.lineSeparator()}：报错文案会被日志与测试断言读取，
     * 固定换行符可让断言在任何平台上表现一致。</p>
     */
    public static final String KEY_GENERATION_COMMANDS =
            "Linux/macOS: openssl rand -base64 48\n"
                    + "Windows PowerShell 7+: [Convert]::ToBase64String("
                    + "[Security.Cryptography.RandomNumberGenerator]::GetBytes(48))\n"
                    + "Windows PowerShell 5.1: $b=New-Object byte[] 48; "
                    + "[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); "
                    + "[Convert]::ToBase64String($b)";
}
