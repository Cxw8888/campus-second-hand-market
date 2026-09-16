package com.campus.market.service;

import com.campus.market.common.query.PageQuery;
import com.campus.market.common.result.PageResult;
import com.campus.market.vo.NotificationVO;

/**
 * 站内信服务（接口 6.x，PROJECT_CONTEXT 3.5）。
 *
 * <p>发送侧：业务状态变更后异步解耦发送（{@link #sendAsync}），失败重试 2 次（固定间隔 2 秒），
 * 2 次仍失败才记录 error 日志并降级，<b>严禁直接丢弃或无限重试</b>。</p>
 * <p>消费侧：list / read / read-all / unread-count；read 必须校验 {@code user_id = 当前登录用户}，否则 code=203。</p>
 *
 * <p>发送侧方法（send / sendAsync）继承自 {@link NotificationSender} 端口，
 * 供订单域等模块解耦依赖。</p>
 */
public interface NotificationService extends NotificationSender {

    /**
     * 分页查询当前登录用户的站内信（按创建时间倒序）。
     *
     * @param isRead 是否已读过滤，null 表示全部
     * @param query  分页参数（page / size）
     */
    PageResult<NotificationVO> list(Boolean isRead, PageQuery query);

    /**
     * 标记单条通知为已读。
     *
     * <p><b>必须校验归属</b>：{@code user_id != 当前登录用户} → code=203。</p>
     *
     * @param id 通知ID
     */
    void markRead(Long id);

    /**
     * 标记当前登录用户全部未读通知为已读。
     *
     * @return 实际更新行数
     */
    int markAllRead();

    /**
     * 当前登录用户未读通知数（前端 30 秒轮询）。
     *
     * @return 未读数量
     */
    long unreadCount();
}
