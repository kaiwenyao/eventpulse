package dev.kaiwen.eventpulse.outbox;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafkaTemplate) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publish() {
        for (OutboxEvent event : outbox.findTop50ByPublishedAtIsNullOrderByIdAsc()) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getDedupKey(), event.getPayload());
                event.setPublishedAt(Instant.now());
            }
            catch (Exception e) {
                log.warn("Outbox 发布失败 id={} type={}", event.getId(), event.getEventType(), e);
                return;
            }
        }
    }

    public long pending() {
        return outbox.countByPublishedAtIsNull();
    }
}
