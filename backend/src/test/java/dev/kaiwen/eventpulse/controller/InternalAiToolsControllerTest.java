package dev.kaiwen.eventpulse.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.AiDtos.ToolSearchRequest;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.UserPreference;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;
import dev.kaiwen.eventpulse.service.EventService;
import dev.kaiwen.eventpulse.service.PlatformService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class InternalAiToolsControllerTest {

    @Mock EventRepository events;
    @Mock UserPreferenceRepository preferences;
    @Mock InteractionRepository interactions;
    @Mock PlatformService platformService;

    private InternalAiToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAiToolsController(new EventService(events), platformService, events,
                preferences, interactions, new SimpleMeterRegistry());
    }

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    private static dev.kaiwen.eventpulse.dto.EventDtos.EventVo vo(long id) {
        Instant start = Instant.now().plusSeconds(3600);
        Instant end = Instant.now().plusSeconds(7200);
        Instant now = Instant.now();
        return new dev.kaiwen.eventpulse.dto.EventDtos.EventVo(
                id, "Event " + id, "s", "d", "music", "Shanghai", "venue", "addr", 31.2, 121.4,
                start, end, null, null, null, 4, null, null, 10000, 100, 40, 60, 9L,
                EventStatus.PUBLISHED, null, null, null, now, now, 1L, null, true, null);
    }

    private static Event event(long id, String status) {
        Event event = new Event();
        event.setId(id);
        event.setTitle("Event " + id);
        event.setSummary("s");
        event.setCategory("music");
        event.setCity("Shanghai");
        event.setStartsAt(Instant.now().plusSeconds(3600));
        event.setEndsAt(Instant.now().plusSeconds(7200));
        event.setPriceCents(10000);
        event.setCapacity(100);
        event.setSold(40);
        event.setOrganiserId(9L);
        event.setStatus(status);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        return event;
    }

    @Test
    void searchReturnsPublicEventsAndCapsLimit() {
        when(events.findByStatusInOrderByStartsAtAsc(any()))
                .thenReturn(java.util.stream.IntStream.rangeClosed(1, 30)
                        .mapToObj(i -> event(i, EventStatus.PUBLISHED))
                        .toList());
        var result = controller.search(new ToolSearchRequest(null, null, "music", null, null, null, null, true, 99))
                .getData();
        // 工具最多返回 20 条 —— 30 条候选不会全部交给 Agent。
        assertThat(result).hasSize(InternalAiToolsController.MAX_TOOL_RESULTS);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    void detailsHideNonPublicEvents() {
        when(events.findById(5L)).thenReturn(Optional.of(event(5L, EventStatus.DRAFT)));
        assertThatThrownBy(() -> controller.details(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
        when(events.findById(6L)).thenReturn(Optional.of(event(6L, EventStatus.PUBLISHED)));
        assertThat(controller.details(6L).getData().id()).isEqualTo(6L);
    }

    @Test
    void nearbyRequiresCoordinatesAndCapsRadius() {
        assertThatThrownBy(() -> controller.nearby(
                new dev.kaiwen.eventpulse.dto.AiDtos.ToolNearbyRequest(null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        when(platformService.nearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(vo(1L)));
        var result = controller.nearby(new dev.kaiwen.eventpulse.dto.AiDtos.ToolNearbyRequest(31.2, 121.4, 500d, 5))
                .getData();
        assertThat(result).hasSize(1);
    }

    @Test
    void popularCapsResults() {
        when(platformService.popular()).thenReturn(java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> vo(i))
                .toList());
        assertThat(controller.popular(3).getData()).hasSize(3);
        assertThat(controller.popular(null).getData()).hasSize(8);
    }

    @Test
    void preferencesRequireSignedUserContext() {
        assertThatThrownBy(() -> controller.myPreferences())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("signed-in user context");

        BaseContext.setUserId(2L);
        when(preferences.findById(2L)).thenReturn(Optional.empty());
        assertThat(controller.myPreferences().getData().categories()).isNull();
        UserPreference pref = new UserPreference();
        pref.setCategories("music");
        pref.setCities("Shanghai");
        when(preferences.findById(2L)).thenReturn(Optional.of(pref));
        assertThat(controller.myPreferences().getData().cities()).isEqualTo("Shanghai");
    }

    @Test
    void recentCategoriesSummariseCountsOnly() {
        BaseContext.setUserId(2L);
        dev.kaiwen.eventpulse.entity.Interaction interaction = new dev.kaiwen.eventpulse.entity.Interaction();
        interaction.setUserId(2L);
        interaction.setEventId(1L);
        when(interactions.findByUserIdOrderByCreatedAtDesc(2L)).thenReturn(List.of(interaction));
        when(events.findAllById(any())).thenReturn(List.of(event(1L, EventStatus.PUBLISHED)));
        var result = controller.myRecentCategories().getData();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo("music");
        assertThat(result.get(0).count()).isEqualTo(1L);
    }
}
