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
     * 卖家确认面交完成（已支付面交单 1→3，批次 6.0.5.1 · M2 新增）。
     *
     * <p>与 {@link #receiveByFace(Long, Long)}（买家确认，1→3）<b>对称</b>：面交场景下买卖双方
     * 谁先确认都行。补这条路径是为了修自审报告 M2 —— 修前已支付的面交单（status=1, trade_type=1）
     * <b>没有任何终态路径</b>：买家失联后卖家只能干等（自动收货只覆盖 status=2），订单永久停在 1。</p>
     *
     * <p>SQL 层三重守卫：{@code status = 1}（未支付/已完成都不放行）、
     * {@code trade_type = 1}（邮寄单严禁走面交完成，与"面交严禁发货"对称）、
     * {@code seller_id = ?}（权限必须用卖家身份校验，严禁用 user_id）。</p>
     */
    @Update("UPDATE tb_order SET status = 3, finish_time = NOW() WHERE id = #{id} AND status = 1 AND trade_type = 1 AND seller_id = #{sellerId} AND is_deleted = 0")
    int finishFaceBySeller(@Param("id") Long id, @Param("sellerId") Long sellerId);

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
     * 定时任务：自动确认收货（单条，批次 6.0.5.2 起同时作为<b>面交单兜底完成</b>）。
     *
     * <p>覆盖两类订单（时间窗口由取数 SQL {@link #selectAutoConfirmCandidates} 把关，这里只做状态守卫）：</p>
     * <ul>
     *   <li>邮寄单：{@code status=2}（已发货待收货）+ {@code trade_type IN (2,3)}；</li>
     *   <li>面交单：{@code status=1}（已支付）+ {@code trade_type = 1} —— 修 6.0.5.1 的遗留
     *       "买卖双方都失联时面交单永久停在 1"（trade_type=1 不可能是 status=2，因为面交严禁发货）。</li>
     * </ul>
     * <p>状态守卫保证并发/重复执行安全：已经被确认（3）或被冻结/退款（5/6/7）的订单不会被它改掉。</p>
     */
    @Update("UPDATE tb_order SET status = 3, finish_time = NOW() WHERE id = #{id} AND is_deleted = 0 "
            + "AND ((status = 2 AND trade_type IN (2,3)) OR (status = 1 AND trade_type = 1))")
    int autoConfirmOne(@Param("id") Long id);

    /**
     * 定时任务取数：自动确认收货的候选订单（单批上限 {@code limit}，先到先处理）。
     *
     * <p>批次 6.0.5.2 的两处变化：</p>
     * <ol>
     *   <li><b>阈值参数化</b>（原来硬编码 {@code INTERVAL 7 DAY}，与 {@code app.task.auto-confirm.days} 脱节）；</li>
     *   <li><b>覆盖面交单</b>（{@code status=1 AND trade_type=1 AND pay_time} 超期）——
     *       面交按"支付时间"计龄，邮寄按"发货时间"。</li>
     * </ol>
     */
    @Select("SELECT * FROM tb_order WHERE is_deleted = 0 AND ("
            + "(status = 2 AND trade_type IN (2,3) AND ship_time < NOW() - INTERVAL #{days} DAY) OR "
            + "(status = 1 AND trade_type = 1 AND pay_time IS NOT NULL AND pay_time < NOW() - INTERVAL #{days} DAY)"
            + ") ORDER BY id ASC LIMIT #{limit}")
    List<Order> selectAutoConfirmCandidates(@Param("days") Integer days, @Param("limit") Integer limit);

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
     * 定时任务取数：处于自动确认收货提醒窗口内的订单。
     *
     * <p>窗口 = {@code ship_time <= NOW() - INTERVAL fromDays DAY AND ship_time > NOW() - INTERVAL toDays DAY}。
     * 批次 6.0.5.2 · 定时任务③把它从"单日区间"放宽为可配置宽度（默认 2 天），
     * 这样应用停机一天不会永久漏提醒；窗口宽度又不足以覆盖那些"早就该被自动确认"的陈旧订单
     * （它们已不在 status=2）。另加 {@code limit} 防止一次拉全表。</p>
     */
    @Select("SELECT * FROM tb_order WHERE status = 2 AND trade_type IN (2,3) AND is_deleted = 0 "
            + "AND ship_time <= NOW() - INTERVAL #{fromDays} DAY AND ship_time > NOW() - INTERVAL #{toDays} DAY "
            + "ORDER BY ship_time ASC LIMIT #{limit}")
    List<Order> selectAutoConfirmRemindOrders(@Param("fromDays") Integer fromDays,
                                              @Param("toDays") Integer toDays,
                                              @Param("limit") Integer limit);

    /**
     * 订单回看已删除商品占位示例：用自定义 SQL 绕过逻辑删除过滤取订单本身；
     * 关联的商品信息由 Service 用自定义 SQL 单独查询，@InterceptorIgnore 不作为主方案。
     */
    @Select("SELECT o.* FROM tb_order o WHERE o.id = #{id}")
    Order selectOrderWithDeletedProduct(@Param("id") Long id);
}
