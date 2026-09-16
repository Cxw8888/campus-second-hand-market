package com.campus.market.service;

/**
 * 站内信发送端口（订单 / 商品 / 管理端等业务域只依赖本接口）。
 *
 * <p>由 {@link NotificationService} 继承并由 {@code NotificationServiceImpl} 实现：
 * 异步发送走专用线程池 {@code notificationExecutor}，失败重试 2 次（固定间隔 2 秒），
 * 2 次仍失败才记录 error 日志降级——<b>严禁直接丢弃或无限重试导致线程池堵塞</b>。</p>
 */
public interface NotificationSender {

    /**
     * 同步发送站内信（事务内使用，与业务同事务落库）。
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
     */
    void sendAsync(Long userId, Integer type, Integer bizType, Long bizId, String content);
}
