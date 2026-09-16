package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.common.query.PageQuery;
import com.campus.market.common.result.PageResult;
import com.campus.market.config.AsyncConfig;
import com.campus.market.entity.Notification;
import com.campus.market.mapper.NotificationMapper;
import com.campus.market.security.UserContext;
import com.campus.market.service.NotificationService;
import com.campus.market.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内信服务实现。
 *
 * <p>异步发送使用专用线程池 {@code notificationExecutor}（核心 8 / 最大 16 / 队列 200 / CallerRunsPolicy），
 * 失败重试 2 次、固定间隔 2 秒，仍失败记录 error 日志降级。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 异步发送失败后的重试次数（总尝试次数 = 1 + 2）。 */
    private static final int MAX_RETRY = 2;

    /** 重试固定间隔（毫秒）。 */
    private static final long RETRY_INTERVAL_MILLIS = 2000L;

    /** 未读标记。 */
    private static final int UNREAD = 0;

    /** 已读标记。 */
    private static final int READ = 1;

    private final NotificationMapper notificationMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void send(Long userId, Integer type, Integer bizType, Long bizId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setBizType(bizType);
        notification.setBizId(bizId == null ? 0L : bizId);
        notification.setContent(content);
        notification.setIsRead(UNREAD);
        notificationMapper.insert(notification);
        log.debug("站内信已入库: userId={}, type={}, bizType={}, bizId={}", userId, type, bizType, bizId);
    }

    @Override
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    public void sendAsync(Long userId, Integer type, Integer bizType, Long bizId, String content) {
        for (int attempt = 1; attempt <= MAX_RETRY + 1; attempt++) {
            try {
                insertNotification(userId, type, bizType, bizId, content);
                if (attempt > 1) {
                    log.info("站内信异步发送第 {} 次尝试成功: userId={}, bizType={}, bizId={}",
                            attempt, userId, bizType, bizId);
                }
                return;
            } catch (Exception e) {
                log.warn("站内信异步发送失败（第 {}/{} 次）: userId={}, bizType={}, bizId={}, err={}",
                        attempt, MAX_RETRY + 1, userId, bizType, bizId, e.getMessage());
                if (attempt > MAX_RETRY) {
                    break;
                }
                try {
                    // 固定间隔 2 秒，最多重试 2 次，严禁无限重试导致线程池堵塞
                    Thread.sleep(RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("站内信重试等待被中断，提前降级: userId={}, bizId={}", userId, bizId);
                    break;
                }
            }
        }
        // 降级：记录 error 日志（业务流程已提交，前端可通过轮询接口感知缺失）
        log.error("站内信异步发送最终失败，已降级: userId={}, type={}, bizType={}, bizId={}, content={}",
                userId, type, bizType, bizId, content);
    }

    @Override
    public PageResult<NotificationVO> list(Boolean isRead, PageQuery query) {
        Long userId = UserContext.requireUserId();
        long current = query == null ? 1L : query.current();
        long size = query == null ? 10L : query.pageSize();

        Page<Notification> page = new Page<>(current, size);
        IPage<Notification> result = notificationMapper.selectPage(page, Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(isRead != null, Notification::getIsRead, Boolean.TRUE.equals(isRead) ? READ : UNREAD)
                .orderByDesc(Notification::getCreateTime)
                .orderByDesc(Notification::getId));
        return PageResult.of(result, NotificationServiceImpl::toVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long id) {
        Long userId = UserContext.requireUserId();
        Notification notification = notificationMapper.selectOne(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId));
        if (notification == null) {
            // 必须校验归属：通知不存在或不属于当前用户 → 203
            throw BusinessException.noPermission("无权操作该通知");
        }
        if (Integer.valueOf(READ).equals(notification.getIsRead())) {
            // 幂等：已读直接返回成功
            return;
        }
        Notification update = new Notification();
        update.setId(notification.getId());
        update.setIsRead(READ);
        notificationMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        Long userId = UserContext.requireUserId();
        Notification update = new Notification();
        update.setIsRead(READ);
        return notificationMapper.update(update, Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, UNREAD));
    }

    @Override
    public long unreadCount() {
        Long userId = UserContext.requireUserId();
        Long count = notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, UNREAD));
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 单次插入（供异步重试复用）。异步线程内自行 insert，不依赖调用方事务。
     */
    private void insertNotification(Long userId, Integer type, Integer bizType, Long bizId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setBizType(bizType);
        notification.setBizId(bizId == null ? 0L : bizId);
        notification.setContent(content);
        notification.setIsRead(UNREAD);
        notificationMapper.insert(notification);
    }

    private static NotificationVO toVO(Notification notification) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setType(notification.getType());
        vo.setBizType(notification.getBizType());
        vo.setBizId(notification.getBizId());
        vo.setContent(notification.getContent());
        vo.setIsRead(notification.getIsRead());
        vo.setCreateTime(notification.getCreateTime());
        return vo;
    }
}
