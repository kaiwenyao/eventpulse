package dev.kaiwen.eventpulse.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 显式创建业务 Topic 与 DLT Topic，两者 partition 数相同（可配置）。
 * 本地开发至少 3 个 partition：多个 Worker 时 Kafka 才能把不同 partition
 * 分配给不同 Worker；同一订单的消息用 message_key 进同一 partition 保序。
 */
@Configuration
@Profile("worker")
public class KafkaTopicConfig {

    private final int partitions;

    public KafkaTopicConfig(@Value("${eventpulse.kafka.topic-partitions:3}") int partitions) {
        this.partitions = partitions;
    }

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_EVENTS)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.BOOKING_EVENTS_DLT)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    /** cart-events：按用户（message key = cart:{userId}）分区保序。 */
    @Bean
    public NewTopic cartEventsTopic() {
        return TopicBuilder.name(KafkaTopics.CART_EVENTS)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic cartEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.CART_EVENTS_DLT)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    /** wallet-events：按用户（message key = wallet:{userId}）分区，携带流水序号。 */
    @Bean
    public NewTopic walletEventsTopic() {
        return TopicBuilder.name(KafkaTopics.WALLET_EVENTS)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic walletEventsDltTopic() {
        return TopicBuilder.name(KafkaTopics.WALLET_EVENTS_DLT)
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
