package dev.kaiwen.eventpulse.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import dev.kaiwen.eventpulse.outbox.KafkaTopicConfig;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;

/**
 * Kafka 分布式行为（真实 broker）：
 * - topic 按可配置的 partition 数创建（复用生产的 KafkaTopicConfig 定义）；
 * - 同组两个 Worker（consumer）被分配不同 partition，且都能消费到消息；
 * - 同一订单（message_key）的消息进同一 partition，并且先创建后取消的顺序不乱。
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaPartitionIT {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
            .waitingFor(Wait.forListeningPort());

    static final String TOPIC = KafkaTopics.BOOKING_EVENTS;
    static final String GROUP = "eventpulse-it";

    @BeforeAll
    static void createTopicsWithConfiguredPartitions() throws Exception {
        // 用生产的 topic 定义创建（等价 worker 启动时 KafkaAdmin 的行为）。
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            KafkaTopicConfig config = new KafkaTopicConfig(3);
            admin.createTopics(List.of(config.bookingEventsTopic(), config.bookingEventsDltTopic()))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private static KafkaConsumer<String, String> worker() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, GROUP,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    /**
     * 重平衡要靠 consumer 在 poll 里推进：只等其中一个、另一个不 poll，
     * 重平衡会永远停不下来。所以两个都轮流驱动，直到都拿到分区。
     */
    private static void waitUntilBothAssigned(KafkaConsumer<String, String> a, KafkaConsumer<String, String> b) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            a.poll(Duration.ofMillis(150));
            b.poll(Duration.ofMillis(150));
            if (!a.assignment().isEmpty() && !b.assignment().isEmpty()) {
                return;
            }
        }
        throw new AssertionError("rebalance did not settle within 30s");
    }

    private static List<ConsumerRecord<String, String>> pollFor(
            KafkaConsumer<String, String> consumer, int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(300)).forEach(records::add);
        }
        return records;
    }

    @Test
    void sameBookingKeyGoesToSamePartitionAndKeepsOrder() throws Exception {
        String created = "{\"type\":\"BOOKING_CREATED\",\"bookingId\":42}";
        String cancelled = "{\"type\":\"BOOKING_CANCELLED\",\"bookingId\":42}";
        RecordMetadata first;
        RecordMetadata second;
        try (KafkaProducer<String, String> p = producer()) {
            first = p.send(new ProducerRecord<>(TOPIC, "booking:42", created)).get(10, TimeUnit.SECONDS);
            second = p.send(new ProducerRecord<>(TOPIC, "booking:42", cancelled)).get(10, TimeUnit.SECONDS);
        }
        // 稳定业务 key：同一订单的消息进同一分区，分区内的先后顺序就是业务顺序。
        assertThat(second.partition()).isEqualTo(first.partition());
        assertThat(second.offset()).isGreaterThan(first.offset());

        try (KafkaConsumer<String, String> consumer = worker()) {
            consumer.subscribe(List.of(TOPIC));
            List<ConsumerRecord<String, String>> records = pollFor(consumer, 2);
            assertThat(records).hasSize(2);
            assertThat(records.get(0).value()).contains("BOOKING_CREATED");
            assertThat(records.get(1).value()).contains("BOOKING_CANCELLED");
        }
    }

    @Test
    void twoWorkersInSameGroupGetDifferentPartitionsAndBothConsume() {
        KafkaConsumer<String, String> workerA = worker();
        KafkaConsumer<String, String> workerB = worker();
        try {
            workerA.subscribe(List.of(TOPIC));
            workerB.subscribe(List.of(TOPIC));
            waitUntilBothAssigned(workerA, workerB);

            Set<TopicPartition> assignedA = workerA.assignment();
            Set<TopicPartition> assignedB = workerB.assignment();
            assertThat(assignedA).isNotEmpty();
            assertThat(assignedB).isNotEmpty();
            // 同一分区不会同时分配给两个 Worker；3 个分区被两个 Worker 分摊完。
            Set<TopicPartition> union = new HashSet<>(assignedA);
            union.addAll(assignedB);
            assertThat(union).hasSize(3);

            // 给每个分区发一条消息：谁被分配了这个分区，谁就该消费到它。
            try (KafkaProducer<String, String> p = producer()) {
                for (TopicPartition tp : union) {
                    p.send(new ProducerRecord<>(TOPIC, tp.partition(), "p" + tp.partition(),
                            "{\"partition\":" + tp.partition() + "}"));
                }
                p.flush();
            }

            Map<Integer, String> receivedByA = collectFrom(workerA, assignedA);
            Map<Integer, String> receivedByB = collectFrom(workerB, assignedB);
            // 两个 Worker 都真实消费了各自的分区，互不越界。
            assertThat(receivedByA.keySet()).containsExactlyInAnyOrderElementsOf(
                    assignedA.stream().map(TopicPartition::partition).toList());
            assertThat(receivedByB.keySet()).containsExactlyInAnyOrderElementsOf(
                    assignedB.stream().map(TopicPartition::partition).toList());
        }
        finally {
            workerA.close(Duration.ofSeconds(5));
            workerB.close(Duration.ofSeconds(5));
        }
    }

    /** 收集本 consumer 分配到的每个分区的一条消息（按分区去重）。 */
    private static Map<Integer, String> collectFrom(KafkaConsumer<String, String> consumer,
            Set<TopicPartition> assigned) {
        Map<Integer, String> byPartition = new java.util.HashMap<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (byPartition.size() < assigned.size() && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                byPartition.putIfAbsent(record.partition(), record.value());
            }
        }
        return byPartition;
    }
}
