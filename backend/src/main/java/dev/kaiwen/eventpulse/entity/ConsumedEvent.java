package dev.kaiwen.eventpulse.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * 消费幂等记录：哪些 (consumer_group, dedup_key) 已经完整处理过。
 * 同一 Kafka 消息被 Outbox 重复投递时，先尝试插入这里；插入 0 行说明以前已处理过。
 */
@Entity
@Table(name = "consumed_events")
@IdClass(ConsumedEvent.Key.class)
public class ConsumedEvent {

    @Id
    @Column(name = "consumer_group", length = 100, nullable = false)
    private String consumerGroup;

    @Id
    @Column(name = "dedup_key", length = 200, nullable = false)
    private String dedupKey;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    public ConsumedEvent() {
    }

    public ConsumedEvent(String consumerGroup, String dedupKey) {
        this.consumerGroup = consumerGroup;
        this.dedupKey = dedupKey;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public static class Key implements Serializable {
        private String consumerGroup;
        private String dedupKey;

        public Key() {
        }

        public Key(String consumerGroup, String dedupKey) {
            this.consumerGroup = consumerGroup;
            this.dedupKey = dedupKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(consumerGroup, key.consumerGroup)
                    && Objects.equals(dedupKey, key.dedupKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, dedupKey);
        }
    }
}