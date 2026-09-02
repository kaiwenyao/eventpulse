package dev.kaiwen.eventpulse.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import dev.kaiwen.eventpulse.outbox.KafkaTopics;

/**
 * Consumer 错误处理：有限重试（每 1 秒一次，最多再试 4 次）后，
 * 把原消息可靠写入 DLT Topic（booking-events.DLT）。
 * DLT 发布失败时也不能把原消息当成处理成功（不会提交 offset）。
 */
@Configuration
@Profile("worker")
public class KafkaErrorHandlerConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        // 显式指定 DLT 目的地为 "原topic + .DLT"，保留原 partition。
        // Spring Kafka 默认的后缀是 "-dlt"（小写），与计划中的 booking-events.DLT 不一致。
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // DLT 发布失败也抛出异常，避免提交原消息的 offset，不假装处理成功。
        recoverer.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 4L));
    }
}