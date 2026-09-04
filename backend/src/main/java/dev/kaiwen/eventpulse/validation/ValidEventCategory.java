package dev.kaiwen.eventpulse.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 字段值必须是 {@link dev.kaiwen.eventpulse.domain.EventCategory} 白名单里的分类。
 *
 * <p>用自定义约束而不是 {@code @Pattern(regexp = "music|tech|...")}：注解参数必须是
 * 编译期常量，写成正则就等于把白名单又抄了一遍，加分类时必然漏改。
 */
@Documented
@Constraint(validatedBy = EventCategoryValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEventCategory {

    String message() default "must be one of the supported categories";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
