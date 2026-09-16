package com.campus.market.service;

import com.campus.market.common.enums.AuditOperationType;
import com.campus.market.entity.AuditLog;
import com.campus.market.mapper.AuditLogMapper;
import com.campus.market.security.LoginUser;
import com.campus.market.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 管理端审计日志服务。
 *
 * <p>规则（PROJECT_CONTEXT 3.7）：所有管理操作与业务<b>同一事务</b>同步写入 tb_audit_log；
 * <b>审计写入失败时捕获异常、记录 error 日志、业务操作仍提交</b>，同时触发告警日志。</p>
 *
 * <p>注意：本类方法使用 {@code REQUIRED} 传播，加入调用方事务；异常在方法内部被捕获，
 * 不向外传播，因此审计失败不会把业务事务标记为 rollback-only —— 这正是文档要求的降级行为。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    /** 成功。 */
    private static final int RESULT_SUCCESS = 1;

    /** 失败。 */
    private static final int RESULT_FAIL = 0;

    private final AuditLogMapper auditLogMapper;

    /**
     * 写入审计日志（与业务同事务；失败降级）。
     *
     * @param operationType 操作类型
     * @param targetType    目标类型（PRODUCT / USER / ORDER / CATEGORY）
     * @param targetId      目标ID
     * @param detail        详情
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void record(AuditOperationType operationType, String targetType, Long targetId, String detail) {
        record(operationType, targetType, targetId, detail, RESULT_SUCCESS);
    }

    /**
     * 写入审计日志（可指定结果）。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void record(AuditOperationType operationType, String targetType, Long targetId,
                       String detail, int result) {
        try {
            LoginUser loginUser = UserContext.get();
            AuditLog auditLog = new AuditLog();
            auditLog.setOperatorId(loginUser == null || loginUser.getUserId() == null ? 0L : loginUser.getUserId());
            auditLog.setOperatorName(loginUser == null ? "SYSTEM" : String.valueOf(loginUser.getUserId()));
            auditLog.setOperationType(operationType.name());
            auditLog.setTargetType(targetType);
            auditLog.setTargetId(targetId == null ? 0L : targetId);
            auditLog.setResult(result);
            auditLog.setDetail(truncate(detail, 500));
            auditLog.setIp(currentIp());
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 降级：审计写入失败不得影响业务提交，记录 error + 触发告警
            log.error("[ALERT] 审计日志写入失败, 业务操作继续提交: operationType={}, targetType={}, targetId={}",
                    operationType, targetType, targetId, e);
        }
    }

    private String truncate(String detail, int maxLength) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= maxLength ? detail : detail.substring(0, maxLength);
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            return com.campus.market.util.IpUtils.getClientIp(request);
        } catch (Exception e) {
            return null;
        }
    }
}
