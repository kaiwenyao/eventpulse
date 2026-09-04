package dev.kaiwen.eventpulse.validation;

import dev.kaiwen.eventpulse.domain.EventCategory;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link ValidEventCategory} 的实现：大小写与首尾空格不敏感的白名单校验。 */
public class EventCategoryValidator implements ConstraintValidator<ValidEventCategory, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null 交给 @NotBlank 报「必填」，这里不重复报错，否则同一个字段会冒出两条消息。
        return value == null || EventCategory.isValid(value);
    }
}
