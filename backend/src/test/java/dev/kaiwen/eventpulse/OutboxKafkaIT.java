package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.kaiwen.eventpulse.outbox.OutboxRelay;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;
import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka path: relay publishes in gapless aggregate order, consumers advance
 * per-aggregate cursors in the same transaction as their side effects, gaps
 * block only that aggregate and are recorded for admin resolution, and
 * poison envelopes end up on the DLT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class OutboxKafkaIT extends IntegrationTestBase {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private DefaultKafkaConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = Map.of(
                "bootstrap.servers", bootstrapServers,
                "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class,
                "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class,
                "auto.offset.reset", "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Test
    void relayPublishesInOrderAndCursorTracksSequences() {
        OrganiserRef fixture = createEventWithTier(10, 10);
        UUID bookingId = UUID.randomUUID();

        // Collect raw Kafka messages independently of the app consumer.
        List<Long> observedSequences = new java.util.concurrent.CopyOnWriteArrayList<>();
        ContainerProperties containerProperties = new ContainerProperties("booking.events.v1");
        containerProperties.setGroupId("test-inspector-" + UUID.randomUUID());
        containerProperties.setMessageListener((MessageListener<String, String>) record -> {
            Map envelope = dev.kaiwen.eventpulse.outbox.OutboxJson.mapper().readValue(record.value(), Map.class);
            if (bookingId.toString().equals(envelope.get("aggregateId"))) {
                observedSequences.add(((Number) envelope.get("aggregateSequence")).longValue());
            }
        });
        KafkaMessageListenerContainer inspector = new KafkaMessageListenerContainer(consumerFactory(),
                containerProperties);
        inspector.start();

        // Three events on the same aggregate must carry sequences 1, 2, 3.
        outbox.append("booking", bookingId, OutboxWriter.TOPIC_BOOKING, "booking.created", Map.of("n", 1));
        outbox.append("booking", bookingId, OutboxWriter.TOPIC_BOOKING, "payment.intent_created", Map.of("n", 2));
        outbox.append("booking", bookingId, OutboxWriter.TOPIC_BOOKING, "booking.confirmed", Map.of("n", 3));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(observedSequences).containsSubsequence(1L, 2L, 3L);
        });
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE state = 'PENDING' AND aggregate_id = ?", Integer.class,
                bookingId);
        assertThat(pending).isZero();
        inspector.stop();
    }

    @Test
    void rolledBackTransactionsLeaveNoSequenceGap() {
        UUID aggregateId = UUID.randomUUID();
        // Committed append takes sequence 1.
        outbox.append("booking", aggregateId, OutboxWriter.TOPIC_BOOKING, "booking.created", Map.of());
        // A rolled-back append must NOT consume sequence 2 permanently.
        try {
            org.springframework.transaction.support.TransactionTemplate txTemplate =
                    new org.springframework.transaction.support.TransactionTemplate(
                            applicationContext().getBean(
                                    org.springframework.transaction.PlatformTransactionManager.class));
            txTemplate.execute(status -> {
                outbox.append("booking", aggregateId, OutboxWriter.TOPIC_BOOKING, "booking.cancelled", Map.of());
                status.setRollbackOnly();
                return null;
            });
        }
        catch (Exception ignored) {
        }
        outbox.append("booking", aggregateId, OutboxWriter.TOPIC_BOOKING, "booking.confirmed", Map.of());
        List<Long> sequences = jdbc.queryForList("""
                SELECT sequence FROM outbox WHERE aggregate_id = ? ORDER BY sequence
                """, Long.class, aggregateId);
        assertThat(sequences).containsExactly(1L, 2L);
    }

    @Test
    void consumerCursorDedupesAndRecordsGaps() {
        UserRef user = createUser("USER");
        UUID bookingId = UUID.randomUUID();
        String topic = "booking.events.v1";
        String groupId = "notification-consumer";

        // A manually injected envelope with sequence 2 while cursor is at 0:
        // gap -> open gap row, no side effect, cursor unchanged.
        publishEnvelope(topic, bookingId, 2, "booking.confirmed",
                Map.of("bookingId", bookingId.toString(), "userId", user.id().toString()));
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer gaps = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM consumer_gaps WHERE aggregate_id = ? AND state = 'OPEN'",
                    Integer.class, bookingId);
            assertThat(gaps).isEqualTo(1);
        });
        Long cursor = jdbc.queryForObject("""
                SELECT last_sequence FROM consumer_cursors WHERE consumer = ? AND aggregate_id = ?
                """, Long.class, groupId, bookingId);
        assertThat(cursor).isZero();
        Integer notifications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, user.id());
        assertThat(notifications).isZero();

        // Admin REBUILD_CURSOR resolution advances to the highest published
        // sequence (none published on this aggregate -> the cursor stays 0 and
        // the gap is marked resolved, unblocking the aggregate).
        UUID gapId = jdbc.queryForObject(
                "SELECT id FROM consumer_gaps WHERE aggregate_id = ? AND state = 'OPEN' LIMIT 1", UUID.class,
                bookingId);
        jdbc.update("UPDATE consumer_gaps SET state = 'RESOLVED_REBUILD', resolved_at = now() WHERE id = ?",
                gapId);
        jdbc.update("""
                UPDATE consumer_cursors SET last_sequence = GREATEST(last_sequence, 1), updated_at = now()
                WHERE consumer = ? AND aggregate_id = ?
                """, groupId, bookingId);

        // Now a sequence-2 duplicate arrives: skipped by the cursor, side
        // effect applied exactly once.
        publishEnvelope(topic, bookingId, 2, "booking.confirmed",
                Map.of("bookingId", bookingId.toString(), "userId", user.id().toString()));
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer delivered = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, user.id());
            assertThat(delivered).isEqualTo(1);
        });
    }

    @Test
    void poisonEnvelopeGoesToDlt() {
        String topic = "booking.events.v1";
        String dlt = topic + ".DLT";
        String poison = "not-a-json-envelope";
        kafkaTemplate.send(topic, "poison-key", poison).join();
        // Inspect the DLT with a throwaway consumer.
        List<String> dltValues = new java.util.concurrent.CopyOnWriteArrayList<>();
        ContainerProperties containerProperties = new ContainerProperties(dlt);
        containerProperties.setGroupId("dlt-inspector-" + UUID.randomUUID());
        containerProperties.setMessageListener((MessageListener<String, String>) (ConsumerRecord<String,
                String> record) -> dltValues.add(record.value()));
        KafkaMessageListenerContainer inspector = new KafkaMessageListenerContainer(consumerFactory(),
                containerProperties);
        inspector.start();
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(dltValues).contains(poison));
        inspector.stop();
    }

    private void publishEnvelope(String topic, UUID aggregateId, long sequence, String eventType,
            Map<String, Object> payload) {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "booking");
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("aggregateSequence", sequence);
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("producer", "test");
        envelope.put("payload", payload);
        kafkaTemplate.send(topic, aggregateId.toString(),
                dev.kaiwen.eventpulse.outbox.OutboxJson.write(envelope)).join();
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    private org.springframework.context.ApplicationContext applicationContext() {
        return context;
    }
}
