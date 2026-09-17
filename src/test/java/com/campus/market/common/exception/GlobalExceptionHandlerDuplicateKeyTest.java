package com.campus.market.common.exception;

import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.filter.RequestIdFilter;
import com.campus.market.common.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} 的 DuplicateKeyException 分支单测（批次 5.4.2）。
 *
 * <h3>为什么必须有这个分支</h3>
 * 唯一索引是数据库层约束，业务层的前置查重看不全它：分类重名时 {@code existsByName}
 * 因 MyBatis-Plus 自动追加 {@code is_deleted = 0} 而放行，INSERT 才抛 DuplicateKeyException。
 * 修复前它会落到兜底分支 → code=500「服务器内部错误」，用户完全无法理解。
 *
 * <p>本用例钉住三件事：① 映射为 code=100（PROJECT_CONTEXT 第 5 章：100 = 参数校验通用）；
 * ② msg 携带**具体字段错误**（分类名/账号/邮箱…），而不是笼统的"操作失败"；
 * ③ 解析不出索引名时退化为通用文案，绝不把 SQL 片段或约束名泄露给前端。</p>
 */
class GlobalExceptionHandlerDuplicateKeyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final MockHttpServletRequest request = newRequest();

    private static MockHttpServletRequest newRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/v1/admin/category");
        req.setAttribute(RequestIdFilter.ATTRIBUTE, "unit-test-request-id");
        return req;
    }

    @Test
    @DisplayName("分类重名 → code=100『分类名称已存在』（修复前是 500）")
    void categoryNameDuplicateShouldReturnCode100() {
        DuplicateKeyException e = mysqlDuplicate("乐器", "tb_category.uk_category_name");

        Result<Void> result = handler.handleDuplicateKey(e, request);

        assertThat(result.getCode())
                .as("必须映射为 100（参数校验），不能是 500")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(result.getMsg()).isEqualTo("分类名称已存在");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("其他已知唯一键也给出对应字段文案（账号 / 邮箱 / 订单号 / 收藏）")
    void otherKnownUniqueKeysShouldMapToTheirOwnMessages() {
        assertThat(handler.handleDuplicateKey(mysqlDuplicate("zhangsan", "tb_user.uk_user_username"), request).getMsg())
                .isEqualTo("该账号已被注册");
        assertThat(handler.handleDuplicateKey(mysqlDuplicate("a@stu.edu.cn", "tb_user.uk_user_email"), request).getMsg())
                .isEqualTo("该邮箱已被注册");
        assertThat(handler.handleDuplicateKey(mysqlDuplicate("123", "tb_order.uk_order_no"), request).getMsg())
                .isEqualTo("订单号重复，请重试");
        assertThat(handler.handleDuplicateKey(mysqlDuplicate("1-2", "tb_favorite.uk_user_product"), request).getMsg())
                .isEqualTo("该商品已在收藏列表中");
    }

    @Test
    @DisplayName("异常被包在外层 cause 里时仍能解析出索引名（MyBatis 的异常转换会套几层）")
    void shouldWalkCauseChainToFindKeyName() {
        DuplicateKeyException e = new DuplicateKeyException(
                "### Error updating database. Cause: org.springframework.dao.DuplicateKeyException: 嵌套",
                new RuntimeException("Duplicate entry '乐器' for key 'tb_category.uk_category_name'"));

        assertThat(handler.handleDuplicateKey(e, request).getMsg()).isEqualTo("分类名称已存在");
    }

    @Test
    @DisplayName("解析不出索引名 → 通用文案，且不泄露 SQL / 表名 / 约束名")
    void unknownKeyShouldFallBackToGenericMessageWithoutLeakingSql() {
        DuplicateKeyException e = new DuplicateKeyException(
                "### Error updating database. Cause: java.sql.SQLIntegrityConstraintViolationException: "
                        + "Duplicate entry 'x' for key 'tb_secret.uk_something_internal'");

        String msg = handler.handleDuplicateKey(e, request).getMsg();

        assertThat(msg).isNotBlank();
        assertThat(msg).doesNotContainIgnoringCase("sql");
        assertThat(msg).doesNotContain("tb_secret").doesNotContain("uk_something_internal");
    }

    @Test
    @DisplayName("回归：207 / 208 / 209 仍走 BusinessException 分支，未被新分支遮蔽")
    void businessCodesShouldStillBeHandledByTheirOwnBranch() {
        assertThat(handler.handleBusinessException(new BusinessException(ErrorCode.CATEGORY_HAS_PRODUCT), request)
                .getBody().getCode())
                .as("208 分类下有商品，禁止删除").isEqualTo(208);
        assertThat(handler.handleBusinessException(new BusinessException(ErrorCode.PRODUCT_HAS_ORDER), request)
                .getBody().getCode())
                .as("207 商品有未完成订单，禁止删除").isEqualTo(207);
        assertThat(handler.handleBusinessException(new BusinessException(ErrorCode.STATUS_NOT_ALLOWED), request)
                .getBody().getCode())
                .as("209 当前状态不允许此操作").isEqualTo(209);
    }

    /** 造一个 MySQL 真实格式的唯一键冲突异常：Duplicate entry 'x' for key '表名.索引名' */
    private static DuplicateKeyException mysqlDuplicate(String value, String key) {
        return new DuplicateKeyException(
                "### Error updating database. Cause: java.sql.SQLIntegrityConstraintViolationException: "
                        + "Duplicate entry '" + value + "' for key '" + key + "'");
    }
}
