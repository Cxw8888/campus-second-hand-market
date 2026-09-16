package com.campus.market.service;

/**
 * 用户级 Token 版本服务（Token version 机制的核心组件）。
 *
 * <p>机制说明（PROJECT_CONTEXT 3.1）：Redis 维护 {@code user:token:version:{userId}}，
 * JWT payload 携带签发时的 version，拦截器每次比对，不一致即 HTTP 401。
 * 因此<b>改密 / 换绑邮箱 / 找回密码 / 封禁 / 解封</b>只需将该 Key +1，
 * 即可让该用户全部已签发 Token 立即失效；这是"使某用户全部 Token 失效"的唯一正确方案
 * （严禁遍历全量 Token 黑名单做模糊匹配）。</p>
 *
 * <p>本服务被 {@code AuthService} / {@code UserService} 直接调用（同步 +1），
 * 也被 {@code AdminService} 在封禁/解封场景于<b>事务提交后</b>调用
 * （{@link #increaseVersionAfterCommit(Long)}，避免事务回滚导致版本被误增）。</p>
 */
public interface TokenVersionService {

    /**
     * 读取用户当前 Token 版本；Key 不存在时初始化为 1 并写回。
     *
     * <p>Redis 故障降级：返回 1，不阻断主流程（拦截器侧有 user:status 兜底）。</p>
     *
     * @param userId 用户ID
     * @return 当前版本号（至少为 1）
     */
    long currentVersion(Long userId);

    /**
     * 版本 +1（改密 / 换绑邮箱 / 找回密码 / 封禁 / 解封）。
     *
     * <p>Key 不存在时先初始化为 1 再 +1，保证首次操作后版本严格递增。</p>
     *
     * @param userId 用户ID
     */
    void increaseVersion(Long userId);

    /**
     * 在<b>当前事务提交后</b>将版本 +1（封禁 / 解封场景使用）。
     *
     * <p>实现要点：</p>
     * <ol>
     *   <li>通过 {@code TransactionSynchronizationManager.registerSynchronization}
     *       注册 afterCommit 回调；</li>
     *   <li>无事务时直接执行，避免回调永不触发；</li>
     *   <li>回调内失败重试 3 次（指数退避 200ms / 400ms / 800ms）；</li>
     *   <li>最终仍失败则记录 ERROR 日志 + 告警日志，<b>不抛出、不阻断主流程</b>
     *       （拦截器兜底查询 {@code user:status:{userId}} 保证封禁仍然生效）。</li>
     * </ol>
     *
     * @param userId 用户ID
     */
    void increaseVersionAfterCommit(Long userId);

    /**
     * 缓存用户状态到 {@code user:status:{userId}}（拦截器版本比对失败时的兜底数据源）。
     *
     * @param userId 用户ID
     * @param status 0-正常, 1-封禁
     */
    void cacheUserStatus(Long userId, Integer status);
}
