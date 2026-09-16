package com.campus.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.market.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理端审计日志 Mapper（只追加）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
