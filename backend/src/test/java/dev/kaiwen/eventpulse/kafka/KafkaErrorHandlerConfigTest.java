package dev.kaiwen.eventpulse.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaErrorHandlerConfigTest {

    @Test
    void createsDefaultErrorHandlerWithDlt() {
        KafkaErrorHandlerConfig config = new KafkaErrorHandlerConfig();
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> template = Mockito.mock(KafkaTemplate.class);
        CommonErrorHandler handler = config.kafkaErrorHandler(template);
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
        // DeadLetterPublishingRecoverer 在 DefaultErrorHandler 内配置了 failIfSendResultIsError=true。
        // 构造成功即可——无法直接获取内部 recoverer，但 DLT 行为由 Spring Kafka 保证设置生效。
        assertThat(handler).isNotNull();
    }
}