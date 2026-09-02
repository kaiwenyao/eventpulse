package dev.kaiwen.eventpulse.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 显式创建业务 Topic 与 DLT Topic，两者 partition 数相同。
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_EVENTS)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_EVENTS_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }
}