package com.campus.market.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置（对应 PROJECT_CONTEXT 3.7 全局配置要求）。
 *
 * <ul>
 *   <li>{@code Long / long → String}：避免前端 JS 精度丢失（雪花算法 order_no 与自增 ID 均可能超过 2^53）；</li>
 *   <li>{@code LocalDateTime → yyyy-MM-dd HH:mm:ss}：与 {@code application.yml} 的
 *       {@code spring.jackson.date-format} 及 {@code AuditLogQuery} 的
 *       {@code @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")} 保持同一约定；</li>
 *   <li>时区 GMT+8 由 {@code application.yml} 的 {@code spring.jackson.time-zone} 统一提供。</li>
 * </ul>
 *
 * <h3>⚠️ 关键陷阱（曾导致 LocalDateTime 序列化 500）</h3>
 * <p>注册自定义 Module 必须用 {@link org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilder#modulesToInstall}
 * ，<b>绝不能</b>用 {@code modules(...)}。二者字节码语义不同（Spring Framework 6.1.6 实测）：</p>
 * <pre>
 *   modules(...)          → findModulesViaServiceLoader = false, findWellKnownModules = false
 *   modulesToInstall(...) → findWellKnownModules = true（且不动 findModulesViaServiceLoader）
 *   构造器默认值           → findModulesViaServiceLoader = false, findWellKnownModules = true
 * </pre>
 * <p>而 {@code JavaTimeModule}（jackson-datatype-jsr310）正是通过 {@code findWellKnownModules}
 * 这条通道自动注册的。一旦被置为 false，JSR-310 类型全部失去序列化器，任何含
 * {@code LocalDateTime} 字段的 VO 都会抛
 * {@code HttpMessageConversionException → InvalidDefinitionException:
 * Java 8 date/time type java.time.LocalDateTime not supported by default}（HTTP 500）。</p>
 */
@Configuration
public class JacksonConfig {

    /** 日期时间格式：与 application.yml 的 date-format 保持一致。 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 日期格式。 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 时间格式。 */
    public static final String TIME_PATTERN = "HH:mm:ss";

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_PATTERN);
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern(TIME_PATTERN);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();

            // ---------- 1. Long / long → String ----------
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);

            // ---------- 2. JSR-310 显式格式化 ----------
            // 注意：spring.jackson.date-format 只作用于 java.util.Date，
            //      对 LocalDateTime 等 JSR-310 类型无效。若不显式指定，
            //      JavaTimeModule 会输出 ISO-8601（形如 2026-09-15T23:30:00），
            //      与 yml 声明的 yyyy-MM-dd HH:mm:ss 不一致。
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER));
            module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));
            module.addSerializer(LocalTime.class, new LocalTimeSerializer(TIME_FORMATTER));
            module.addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));

            // ---------- 3. 注册 ----------
            // 必须是 modulesToInstall：它会保留 findWellKnownModules = true，
            // 从而让 JavaTimeModule 等 well-known 模块继续自动注册。
            // 改成 modules(...) 会同时关闭两条自动注册通道，直接导致本类注释所述的 500。
            builder.modulesToInstall(module);

            // ---------- 4. 日期不序列化成时间戳数组 ----------
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }
}
