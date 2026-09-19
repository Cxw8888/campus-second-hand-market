package com.campus.market.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.campus.market.dto.user.UpdateProfileRequest;
import com.campus.market.entity.User;
import com.campus.market.mapper.UserMapper;
import com.campus.market.security.LoginUser;
import com.campus.market.security.UserContext;
import com.campus.market.service.EmailCodeService;
import com.campus.market.service.StorageService;
import com.campus.market.service.TokenVersionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6.0.5.2 安全加固 · M6-A3 单测（第二处）：换头像时清理旧头像文件。
 *
 * <p>与商品图同理：修前 {@code StorageService.delete} 没有任何调用方，
 * 用户反复换头像会让旧头像永久留在磁盘上。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAvatarCleanupTest {

    private static final Long USER_ID = 7201L;
    private static final String OLD_AVATAR = "/static/uploads/product/7201/old-avatar.jpg";
    private static final String NEW_AVATAR = "/static/uploads/product/7201/new-avatar.jpg";

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenVersionService tokenVersionService;

    @Mock
    private EmailCodeService emailCodeService;

    @Mock
    private StorageService storageService;

    private UserServiceImpl userService;

    /**
     * 补齐 MyBatis-Plus 实体元数据缓存：{@code updateProfile} 用了
     * {@code lambdaUpdate().set(User::getNickname, ...)} 与 {@code lambdaQuery().select(User::getId, ...)}，
     * 二者都会立即翻译列名，缺 TableInfo 就报 {@code can not find lambda cache}（6.0.3 起反复踩到的坑）。
     */
    @BeforeAll
    static void initMybatisPlusEntityMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, passwordEncoder, tokenVersionService,
                emailCodeService, storageService);

        User current = new User();
        current.setId(USER_ID);
        current.setAvatar(OLD_AVATAR);
        when(userMapper.selectOne(any())).thenReturn(current);
        UserContext.set(new LoginUser(USER_ID, 0, 0L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static UpdateProfileRequest avatarRequest(String avatar) {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setAvatar(avatar);
        return request;
    }

    @Test
    @DisplayName("① 换头像 → 删除旧头像文件")
    void changingAvatarShouldDeleteOldFile() {
        userService.updateProfile(avatarRequest(NEW_AVATAR));

        verify(storageService).delete(OLD_AVATAR);
    }

    @Test
    @DisplayName("② 头像没变 → 不删（避免把正在使用的头像删掉）")
    void unchangedAvatarShouldNotBeDeleted() {
        userService.updateProfile(avatarRequest(OLD_AVATAR));

        verify(storageService, never()).delete(anyString());
    }

    @Test
    @DisplayName("③ 本次没有传 avatar 字段 → 不删")
    void absentAvatarFieldShouldNotDelete() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("新昵称");

        userService.updateProfile(request);

        verify(storageService, never()).delete(anyString());
    }

    @Test
    @DisplayName("④ 删除失败 → 只告警，不阻塞资料更新")
    void deleteFailureShouldNotBreakProfileUpdate() {
        doThrow(new RuntimeException("磁盘删除失败")).when(storageService).delete(anyString());

        assertThatCode(() -> userService.updateProfile(avatarRequest(NEW_AVATAR))).doesNotThrowAnyException();
        verify(userMapper).update(any(), any());
    }
}
