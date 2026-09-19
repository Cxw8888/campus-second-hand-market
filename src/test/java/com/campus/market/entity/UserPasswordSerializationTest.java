package com.campus.market.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 6.0.6 · 自审 Minor 7 的回归锁定：{@link User#getPassword()} 永不进入 JSON。
 *
 * <p>自审时这条是"隐患"而不是"漏洞"——当前所有 Controller 返回的都是 VO / Map，
 * <b>没有任何地方直接序列化 User 实体</b>。但正因为它现在是靠"没人写错"来保证的，
 * 一旦将来有人图省事在某个接口里 {@code return User}，BCrypt 哈希就会跟着响应体出门
 * （哈希本身不可逆，但离线爆破成本骤降，属典型"不该出现的信息泄露"）。</p>
 *
 * <p>所以这里用 {@code @JsonProperty(access = WRITE_ONLY)} 把约束<b>钉在实体上</b>，
 * 并加一条单测防止有人"顺手删掉那行注解"（删了解析仍然正常、业务也正常，只有这条用例会红）。</p>
 */
class UserPasswordSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static User user() {
        User user = new User();
        user.setId(1001L);
        user.setUsername("20210001");
        user.setNickname("小明");
        user.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        user.setEmail("20210001@stu.edu.cn");
        return user;
    }

    @Test
    @DisplayName("① 序列化 User → 响应体里绝不能出现 password 字段")
    void passwordIsNeverSerialized() throws Exception {
        String json = objectMapper.writeValueAsString(user());

        assertThat(json)
                .as("序列化结果：%s", json)
                .doesNotContain("password")
                .doesNotContain("$2a$10$abcdefghijklmnopqrstuv");
        // 其它字段必须照常输出（别把 WRITE_ONLY 写成 @JsonIgnore 而误伤反序列化/业务）
        assertThat(json)
                .contains("\"username\":\"20210001\"")
                .contains("\"nickname\":\"小明\"");
    }

    @Test
    @DisplayName("② 反序列化仍然接受 password（WRITE_ONLY = 只写不读，不是完全忽略）")
    void passwordIsStillDeserializable() throws Exception {
        String json = "{\"username\":\"20210002\",\"password\":\"$2a$10$xyz\"}";

        User parsed = objectMapper.readValue(json, User.class);

        assertThat(parsed.getPassword()).isEqualTo("$2a$10$xyz");
        assertThat(parsed.getUsername()).isEqualTo("20210002");
    }
}
