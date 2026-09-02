package dev.kaiwen.eventpulse.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.kaiwen.eventpulse.entity.OutboxEvent;

class KafkaTopicConfigTest {

    @Test
    void createsBusinessAndDltTopicsWithSamePartitions() {
        KafkaTopicConfig config = new KafkaTopicConfig();
        var business = config.bookingEventsTopic();
        var dlt = config.bookingEventsDltTopic();
        assertThat(business.name()).isEqualTo("booking-events");
        assertThat(dlt.name()).isEqualTo("booking-events.DLT");
        assertThat(business.numPartitions()).isEqualTo(dlt.numPartitions());
        assertThat(KafkaTopics.BOOKING_EVENTS).isEqualTo("booking-events");
        assertThat(KafkaTopics.BOOKING_EVENTS_DLT).isEqualTo("booking-events.DLT");
    }

    @Test
    void relayPublishInterruptedStopsRound() throws Exception {
        var outbox = org.mockito.Mockito.mock(dev.kaiwen.eventpulse.repository.OutboxRepository.class);
        var kafka = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        var status = org.mockito.Mockito.mock(OutboxStatusService.class);
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setTopic("booking-events");
        event.setDedupKey("k");
        event.setPayload("{}");
        org.mockito.Mockito.when(outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc())
                .thenReturn(java.util.List.of(event));
        java.util.concurrent.CompletableFuture cf = new java.util.concurrent.CompletableFuture<>();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(cf);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 12L);
        Thread current = Thread.currentThread();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException ignored) {
            }
            current.interrupt();
        });
        relay.publish();
        org.mockito.Mockito.verify(status, org.mockito.Mockito.never()).markPublished(org.mockito.ArgumentMatchers.any(Long.class));
    }
}