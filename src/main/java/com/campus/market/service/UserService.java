package com.campus.market.service;

import com.campus.market.dto.user.ChangeEmailRequest;
import com.campus.market.dto.user.ChangePasswordRequest;
import com.campus.market.dto.user.UpdateProfileRequest;
import com.campus.market.vo.UserVO;

/**
 * 用户服务（接口 1.6 - 1.9）。
 *
 * <p>安全约束（PROJECT_CONTEXT 3.1）：</p>
 * <ul>
 *   <li>改密需校验旧密码，新密码不得与旧密码相同，且必须通过强密码校验；</li>
 *   <li>换绑邮箱需校验登录密码 + 新邮箱验证码；</li>
 *   <li>改密 / 换绑邮箱成功后 {@code user:token:version:{userId}} +1，使该用户全部 Token 立即失效；</li>
 *   <li>对外输出统一使用 {@link UserVO}，<b>绝不包含 password</b>。</li>
 * </ul>
 */
public interface UserService {

    /**
     * 查询当前登录用户资料（接口 1.6）。
     *
     * @return 用户信息 VO（无 password 字段）
     */
    UserVO getProfile();

    /**
     * 更新个人资料（接口 1.7，非全量更新：null 字段保持原值）。
     *
     * @param request 资料更新请求
     */
    void updateProfile(UpdateProfileRequest request);

    /**
     * 修改密码（接口 1.8）：校验旧密码 → 新密码不得与旧密码相同 → 强密码校验 → 更新并提升 Token 版本。
     *
     * @param request 修改密码请求
     */
    void changePassword(ChangePasswordRequest request);

    /**
     * 换绑邮箱（接口 1.9）：校验登录密码 → 新邮箱未被占用 → 校验新邮箱验证码 → 更新并提升 Token 版本。
     *
     * @param request 换绑邮箱请求
     */
    void changeEmail(ChangeEmailRequest request);
}
