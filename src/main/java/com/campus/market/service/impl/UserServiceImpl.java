package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.market.common.enums.ErrorCode;
import com.campus.market.common.exception.BusinessException;
import com.campus.market.dto.user.ChangeEmailRequest;
import com.campus.market.dto.user.ChangePasswordRequest;
import com.campus.market.dto.user.UpdateProfileRequest;
import com.campus.market.entity.User;
import com.campus.market.mapper.UserMapper;
import com.campus.market.security.UserContext;
import com.campus.market.service.EmailCodeService;
import com.campus.market.service.StorageService;
import com.campus.market.service.TokenVersionService;
import com.campus.market.service.UserService;
import com.campus.market.util.PasswordValidator;
import com.campus.market.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 用户服务实现（资料查询 / 更新 / 改密 / 换绑邮箱）。
 *
 * <p>Token version：改密与换绑邮箱成功后 {@code user:token:version:{userId}} +1，
 * 使该用户全部已签发 Token 立即失效（1.7 版本接口验收项）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionService tokenVersionService;
    private final EmailCodeService emailCodeService;
    /** 头像文件清理（批次 6.0.5.2 · M6-A3）：换头像后删除旧图。 */
    private final StorageService storageService;

    @Override
    public UserVO getProfile() {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.unauthorized();
        }
        return UserVO.from(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(UpdateProfileRequest request) {
        Long userId = UserContext.requireUserId();
        User current = ensureUserExists(userId);

        // 非全量更新：仅更新非 null 字段（允许显式传入空串以清空可选字段）
        var wrapper = Wrappers.<User>lambdaUpdate().eq(User::getId, userId);
        boolean changed = false;
        if (request.getNickname() != null) {
            wrapper.set(User::getNickname, request.getNickname().trim());
            changed = true;
        }
        if (request.getPhone() != null) {
            wrapper.set(User::getPhone, request.getPhone().trim());
            changed = true;
        }
        String oldAvatar = current.getAvatar();
        String newAvatar = request.getAvatar() == null ? null : request.getAvatar().trim();
        if (request.getAvatar() != null) {
            wrapper.set(User::getAvatar, newAvatar);
            changed = true;
        }
        if (!changed) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "没有需要更新的资料字段");
        }
        userMapper.update(null, wrapper);
        log.info("个人资料更新成功: userId={}", userId);

        // 批次 6.0.5.2 · M6-A3：换了头像就删掉旧图（先更 DB 再动文件；失败只告警不阻塞）
        if (newAvatar != null && oldAvatar != null && !oldAvatar.equals(newAvatar)) {
            try {
                storageService.delete(oldAvatar);
                log.info("旧头像已清理: userId={}", userId);
            } catch (Exception e) {
                log.warn("删除旧头像失败（降级，不阻塞业务）: userId={}, err={}", userId, e.getMessage());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordRequest request) {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.unauthorized();
        }

        // ① 校验旧密码
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            log.warn("修改密码失败：旧密码不正确, userId={}", userId);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "旧密码不正确");
        }

        // ② 新密码不得与旧密码相同
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "新密码不能与旧密码相同");
        }

        // ③ 强密码校验（8-20 位 + 字母 + 数字 + 特殊字符 + 非弱密码）
        PasswordValidator.validate(request.getNewPassword());

        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(update);

        // ④ 改密成功后 version +1：该用户全部 Token 立即失效（原 Token 访问返回 401）
        tokenVersionService.increaseVersion(userId);
        log.info("修改密码成功，Token version 已提升: userId={}", userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeEmail(ChangeEmailRequest request) {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.unauthorized();
        }

        // ① 校验登录密码（二次确认）
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("换绑邮箱失败：登录密码不正确, userId={}", userId);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "密码不正确");
        }

        String newEmail = request.getNewEmail().trim().toLowerCase(Locale.ROOT);

        // ② 新邮箱不能与他人重复
        Long duplicated = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, newEmail)
                .ne(User::getId, userId));
        if (duplicated != null && duplicated > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该邮箱已被其他账号绑定");
        }

        // ③ 校验新邮箱验证码（格式 / 后缀 / 限流 / 锁定 / 失败计量均在 EmailCodeService 内处理）
        //    scene=BIND_EMAIL（批次 6.0.6 · Minor 6）：与取码时的场景必须一致
        emailCodeService.verify(newEmail, EmailCodeService.SCENE_BIND_EMAIL, request.getEmailCode());

        User update = new User();
        update.setId(userId);
        update.setEmail(newEmail);
        userMapper.updateById(update);

        // ④ 换绑成功后 version +1：该用户全部 Token 立即失效
        tokenVersionService.increaseVersion(userId);
        log.info("换绑邮箱成功，Token version 已提升: userId={}", userId);
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 校验用户存在并返回（<b>返回实体</b>是为了拿到旧头像做文件清理，批次 6.0.5.2 · M6-A3）。
     */
    private User ensureUserExists(Long userId) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getAvatar)
                .eq(User::getId, userId));
        if (user == null) {
            throw BusinessException.unauthorized();
        }
        return user;
    }
}
