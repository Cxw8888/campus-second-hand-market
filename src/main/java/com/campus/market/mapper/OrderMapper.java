package com.campus.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.market.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单 Mapper，含状态机迁移与定时任务 SQL（全部返回影响行数，0 表示状态不被允许）。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 支付（0→1）：仅待支付订单可支付。
     */
    @Update("UPDATE tb_order SET status = 1, pay_time = NOW() WHERE id = #{id} AND status = 0 AND is_deleted = 0")
    int pay(@Param("id") Long id);

    /**
     * 买家主动取消（0→4）：校验 user_id 归属，cancel_by 记录操作人。
     */
    @Update("UPDATE tb_order SET status = 4, cancel_time = NOW(), cancel_by = #{userId}, cancel_reason = #{reason} WHERE id = #{id} AND status = 0 AND user_id = #{userId} AND is_deleted = 0")
    int cancelByBuyer(@Param("id") Long id, @Param("userId") Long userId, @Param("reason") String reason);

    /**
     * 超时未支付自动取消（0→4）：cancel_by = 0 代表系统操作。
     */
    @Update("UPDATE tb_order SET status = 4, cancel_time = NOW(), cancel_by = 0, cancel_reason = '超时未支付自动取消' WHERE id = #{id} AND status = 0 AND is_deleted = 0")
    int cancelByTimeout(@Param("id") Long id);

    /**
     * 卖家发货（1→2）：仅邮寄类订单（2/3）可发货，校验 seller_id 归属。
     */
    @Update("UPDATE tb_order SET status = 2, ship_time = NOW() WHERE id = #{id} AND status = 1 AND seller_id = #{sellerId} AND trade_type IN (2,3) AND is_deleted = 0")
    int ship(@Param("id") Long id, @Param("sellerId") Long sellerId);

    /**
     * 买家确认收货（邮寄 2→3）：校验 user_id 归属。
     */
    @Update("UPDATE tb_order SET status = 3, finish_time = NOW() WHERE id = #{id} AND status = 2 AND trade_type IN (2,3) AND user_id = #{userId} AND is_deleted = 0")
    int receiveByMail(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 买家确认收货（面交 1→3）：面交订单跳过发货状态。
     */
    @Update("UPDATE tb_order SET status = 3, finish_time = NOW() WHERE id = #{id} AND status = 1 AND trade_type = 1 AND user_id = #{userId} AND is_deleted = 0")
    int receiveByFace(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 面交直接完成（0→3）：面交订单跳过支付，由卖家确认见面交易完成，权限校验必须用 seller_id。
     */
    @Update("UPDATE tb_order SET status = 3, finish_time = NOW() WHERE id = #{id} AND status = 0 AND trade_type = 1 AND seller_id = #{sellerId} AND is_deleted = 0")
    int finishFaceToFace(@Param("id") Long id, @Param("sellerId") Long sellerId);

    /**
     * 买家申请退款（1/2→6）：校验 user_id 归属。
     */
    @Update("UPDATE tb_order SET status = 6, refund_apply_time = NOW(), refund_reason = #{reason} WHERE id = #{id} AND status IN (1,2) AND user_id = #{userId} AND is_deleted = 0")
    int applyRefund(@Param("id") Long id, @Param("userId") Long userId, @Param("reason") String reason);

    /**
     * 卖家同意退款（6→4）：校验 seller_id 归属，调用方需同一事务回补库存。
     */
    @Update("UPDATE tb_order SET status = 4, cancel_time = NOW(), cancel_reason = '卖家同意退款' WHERE id = #{id} AND status = 6 AND seller_id = #{sellerId} AND is_deleted = 0")
    int agreeRefund(@Param("id") Long id, @Param("sellerId") Long sellerId);

    /**
     * 卖家拒绝退款（6→7）：进入 3 天申诉期。
     */
    @Update("UPDATE tb_order SET status = 7, refund_reject_time = NOW(), refund_reject_reason = #{reason} WHERE id = #{id} AND status = 6 AND seller_id = #{sellerId} AND is_deleted = 0")
    int rejectRefund(@Param("id") Long id, @Param("sellerId") Long sellerId, @Param("reason") String reason);

    /**
     * 管理员强制退款（6/7→4）：调用方需同一事务回补库存并写审计日志。
     */
    @Update("UPDATE tb_order SET status = 4, cancel_time = NOW(), cancel_reason = #{reason} WHERE id = #{id} AND status IN (6,7) AND is_deleted = 0")
    int forceRefund(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 用户封禁冻结订单（0/1/2/6→5）：SQL 条件括号不可省略，调用方需同步回补库存。
     */
    @Update("UPDATE tb_order SET status = 5 WHERE (seller_id = #{userId} OR user_id = #{userId}) AND status IN (0,1,2,6) AND is_deleted = 0")
    int freezeByUser(@Param("userId") Long userId);

    /**
     * 管理员解冻转取消（5→4）：调用方需同步回补库存。
     */
    @Update("UPDATE tb_order SET status = 4, cancel_time = NOW(), cancel_reason = '管理员解冻取消' WHERE id = #{id} AND status = 5 AND is_deleted = 0")
    int unfreezeToCancel(@Param("id") Long id);

    /**
     * 管理员解冻转线下完成（5→3）。
     */
    @Update("UPDATE tb_order SET status = 3, finish_time = NOW() WHERE id = #{id} AND status = 5 AND is_deleted = 0")
    int unfreezeToComplete(@Param("id") Long id);

    /**
     * 定时任务：发货满 7 天自动确认收货（2→3）。
     */
    @Update("UPDATE tb_order SET status = 3, finish_time = NOW() WHERE status = 2 AND ship_time < NOW() - INTERVAL 7 DAY AND trade_type IN (2,3) AND is_deleted = 0")
    int autoConfirmReceive();

    /**
     * 定时任务：退款被拒 3 天申诉期满后自动恢复原状态（未发货→1，已发货→2）。
     */
    @Update("UPDATE tb_order SET status = CASE WHEN ship_time IS NULL THEN 1 ELSE 2 END WHERE status = 7 AND refund_reject_time < NOW() - INTERVAL 3 DAY AND is_deleted = 0")
    int recoverFromRefundRejected();

    /**
     * 定时任务取数：查询超过指定分钟数仍未支付的订单（0-待支付不参与冻结，可被超时取消）。
     */
    @Select("SELECT * FROM tb_order WHERE status = 0 AND is_deleted = 0 AND create_time < NOW() - INTERVAL #{minutes} MINUTE ORDER BY create_time ASC LIMIT #{limit}")
    List<Order> selectTimeoutPendingOrders(@Param("minutes") Integer minutes, @Param("limit") Integer limit);

    /**
     * 定时任务取数：查询处于自动确认收货提醒窗口（发货后 fromDays~toDays 天）的订单。
     */
    @Select("SELECT * FROM tb_order WHERE status = 2 AND trade_type IN (2,3) AND is_deleted = 0 AND ship_time <= NOW() - INTERVAL #{fromDays} DAY AND ship_time > NOW() - INTERVAL #{toDays} DAY")
    List<Order> selectAutoConfirmRemindOrders(@Param("fromDays") Integer fromDays, @Param("toDays") Integer toDays);

    /**
     * 订单回看已删除商品占位示例：用自定义 SQL 绕过逻辑删除过滤取订单本身；
     * 关联的商品信息由 Service 用自定义 SQL 单独查询，@InterceptorIgnore 不作为主方案。
     */
    @Select("SELECT o.* FROM tb_order o WHERE o.id = #{id}")
    Order selectOrderWithDeletedProduct(@Param("id") Long id);
}
