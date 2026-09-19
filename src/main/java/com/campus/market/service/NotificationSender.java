package com.campus.market.service;

/**
 * 站内信发送端口（订单 / 商品 / 管理端等业务域只依赖本接口）。
 *
 * <p>由 {@link NotificationService} 继承并由 {@code NotificationServiceImpl} 实现：</p>
 * <ul>
 *   <li>{@link #send}：与业务同事务落库（需要"同生共死"时用）；</li>
 *   <li>{@link #sendAsync}：<b>批次 6.0.4 · M3 起在事务提交后</b>才派发 ——
 *       有事务则注册 {@code afterCommit} 回调，无事务立即派发；
 *       真正的异步走专用线程池 {@code notificationExecutor}，失败重试 2 次（固定间隔 2 秒），
 *       2 次仍失败才记录 error 日志降级 —— <b>严禁直接丢弃或无限重试导致线程池堵塞</b>。</li>
 * </ul>
 */
public interface NotificationSender {

    /**
     * 同步发送站内信（与业务同事务落库）。
     *
     * @param userId  接收者ID
     * @param type    通知类型：1-订单, 2-审核, 3-系统
     * @param bizType 业务类型：1-订单, 2-商品, 3-系统
     * @param bizId   业务ID（系统通知=0），前端据此跳转详情
     * @param content 通知内容
     */
    void send(Long userId, Integer type, Integer bizType, Long bizId, String content);

    /**
     * 异步发送站内信（状态变更后调用，避免阻塞主交易流程）。
     *
     * <p><b>事务语义（6.0.4 · M3）</b>：方法内部的派发被推迟到当前事务
     * {@code afterCommit}；事务回滚则<b>不发</b>。因此调用方写在本方法之后的语句
     * 一旦失败导致回滚，用户不会再收到"假通知"。</p>
     */
    void sendAsync(Long userId, Integer type, Integer bizType, Long bizId, String content);
}
