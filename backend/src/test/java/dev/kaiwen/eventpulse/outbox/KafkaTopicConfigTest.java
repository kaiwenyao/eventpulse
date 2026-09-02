package dev.kaiwen.eventpulse.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KafkaTopicConfigTest {

    @Test
    void createsBusinessAndDltTopicsWithSameConfiguredPartitions() {
        KafkaTopicConfig config = new KafkaTopicConfig(3);
        var business = config.bookingEventsTopic();
        var dlt = config.bookingEventsDltTopic();
        assertThat(business.name()).isEqualTo("booking-events");
        assertThat(dlt.name()).isEqualTo("booking-events.DLT");
        assertThat(business.numPartitions()).isEqualTo(3);
        // DLT 与业务 Topic partition 数相同，DLT 发布器才能保留原 partition。
        assertThat(business.numPartitions()).isEqualTo(dlt.numPartitions());
        assertThat(KafkaTopics.BOOKING_EVENTS).isEqualTo("booking-events");
        assertThat(KafkaTopics.BOOKING_EVENTS_DLT).isEqualTo("booking-events.DLT");
    }

    @Test
    void partitionCountIsConfigurableForLocalSingleBroker() {
        assertThat(new KafkaTopicConfig(1).bookingEventsTopic().numPartitions()).isEqualTo(1);
    }
}
