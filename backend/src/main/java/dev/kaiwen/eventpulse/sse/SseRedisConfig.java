package dev.kaiwen.eventpulse.sse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * API 角色的 Redis 订阅装配：把 {@link SseEventSubscriber} 挂到广播频道上。
 * 仅在 Redis 启用时创建；Redis 短暂不可用时容器自动重试，不影响 REST 功能。
 */
@Configuration
@Profile("api")
@ConditionalOnProperty(name = "eventpulse.redis-enabled", havingValue = "true")
public class SseRedisConfig {

    @Bean
    public RedisMessageListenerContainer sseListenerContainer(
            LettuceConnectionFactory redisConnectionFactory, SseEventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(SseReminder.REDIS_CHANNEL));
        return container;
    }
}
