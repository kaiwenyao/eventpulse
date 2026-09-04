package dev.kaiwen.eventpulse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.dto.EventDtos.EventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.OrganiserEventRequest;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.service.EventService;
import dev.kaiwen.eventpulse.validation.EventCategoryValidator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * 分类白名单三层防线里的前两层：常量表本身，以及 DTO 校验 + 写入归一化。
 * 第三层（数据库 CHECK 约束）由 {@code SchemaBaselineIT} 覆盖。
 */
class EventCategoryTest {

    @AfterEach
    void clearContext() {
        BaseContext.clear();
    }

    @Test
    void allListsExactlyTheEightFixedCategories() {
        assertThat(EventCategory.ALL).containsExactly(
                "music", "tech", "sports", "art", "food", "business", "community", "other");
    }

    @Test
    void isValidIgnoresCaseAndSurroundingWhitespace() {
        assertThat(EventCategory.isValid("music")).isTrue();
        assertThat(EventCategory.isValid("  MUSIC  ")).isTrue();
        assertThat(EventCategory.isValid("Community")).isTrue();

        assertThat(EventCategory.isValid("工作坊")).isFalse();
        assertThat(EventCategory.isValid("音乐")).isFalse();
        assertThat(EventCategory.isValid("")).isFalse();
        assertThat(EventCategory.isValid(null)).isFalse();
    }

    @Test
    void normaliseLowercasesAndTrims() {
        assertThat(EventCategory.normalise(" Music ")).isEqualTo("music");
        assertThat(EventCategory.normalise("SPORTS")).isEqualTo("sports");
    }

    @Test
    void normaliseRejectsAnythingOutsideTheWhitelist() {
        // 分类固定之前主办方能随手填「工作坊」，这类值必须在入库前被挡下。
        assertThatThrownBy(() -> EventCategory.normalise("工作坊"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category")
                .hasMessageContaining("music");
        assertThatThrownBy(() -> EventCategory.normalise(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void filterCsvKeepsOnlyWhitelistedPreferences() {
        assertThat(EventCategory.filterCsv("music,工作坊,ART")).isEqualTo("music,art");
        assertThat(EventCategory.filterCsv("music, music ,tech")).isEqualTo("music,tech");
        // 一个合法值都不剩就返回 null，而不是在库里留一个空串。
        assertThat(EventCategory.filterCsv("工作坊,音乐")).isNull();
        assertThat(EventCategory.filterCsv(null)).isNull();
        assertThat(EventCategory.filterCsv("")).isEmpty();
    }

    @Test
    void validatorDefersNullToNotBlank() {
        EventCategoryValidator validator = new EventCategoryValidator();
        // null 交给 @NotBlank 报「必填」，这里不重复报错，否则同一字段冒出两条消息。
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("music", null)).isTrue();
        assertThat(validator.isValid("工作坊", null)).isFalse();
    }

    @Test
    void beanValidationRejectsAnUnknownCategoryOnBothRequestDtos() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(eventRequest("工作坊")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("category");
            assertThat(validator.validate(eventRequest("music"))).isEmpty();

            assertThat(validator.validate(organiserRequest("工作坊")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("category");
            assertThat(validator.validate(organiserRequest("community"))).isEmpty();
        }
    }

    @Test
    void createNormalisesTheStoredCategory() {
        EventRepository events = mock(EventRepository.class);
        EventService service = new EventService(events);
        BaseContext.setUserId(1L);
        BaseContext.setRole("ORGANISER");
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Bean Validation 对大小写不敏感，所以 "MUSIC" 能进到这里；入库必须是小写
        // slug，否则发现页的精确匹配筛选就找不到这个活动。
        assertThat(service.create(eventRequest("MUSIC")).category()).isEqualTo("music");
    }

    @Test
    void updateNormalisesTheStoredCategory() {
        EventRepository events = mock(EventRepository.class);
        EventService service = new EventService(events);
        BaseContext.setUserId(1L);
        BaseContext.setRole("ORGANISER");

        Event existing = new Event();
        existing.setId(1L);
        existing.setOrganiserId(1L);
        existing.setCategory("music");
        existing.setSold(0);
        existing.setStatus(EventStatus.PUBLISHED);
        when(events.findById(1L)).thenReturn(Optional.of(existing));

        service.update(1L, eventRequest(" Art "));
        assertThat(existing.getCategory()).isEqualTo("art");
    }

    private static EventRequest eventRequest(String category) {
        return new EventRequest("New event", "desc", category, "Shanghai", Instant.now(), 100, 20);
    }

    private static OrganiserEventRequest organiserRequest(String category) {
        return new OrganiserEventRequest(
                "New event", "summary", "desc", category, null, null, Instant.now(), null,
                "Berlin", null, null, null, null, 100, 20, null, null, null, null, null, null, false);
    }
}
