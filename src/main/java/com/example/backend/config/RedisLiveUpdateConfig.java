package com.example.backend.config;

import com.example.backend.projections.LiveUpdateBroadcaster;
import com.example.backend.projections.RedisLiveUpdateListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisLiveUpdateConfig {

    @Bean
    ChannelTopic sessionUpdatesTopic() {
        return new ChannelTopic(LiveUpdateBroadcaster.REDIS_CHANNEL);
    }

    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisLiveUpdateListener listener,
            ChannelTopic sessionUpdatesTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, sessionUpdatesTopic);
        return container;
    }
}
