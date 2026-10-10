package com.example.backend.projections;

import com.example.backend.events.DomainEvent;
import com.example.backend.events.EventHandler;
import com.example.backend.events.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class LiveUpdateBroadcaster implements EventHandler {

    private final StringRedisTemplate redisTemplate;

    public static final String REDIS_CHANNEL = "poker-ledger.session-updates";

    private static final Set<String> MUTATING_EVENTS = Set.of(
            EventType.PLAYER_ADDED,
            EventType.BUY_IN_ADDED,
            EventType.CASH_OUT_ADDED,
            EventType.HAND_PLAYED,
            EventType.SESSION_CLOSED,
            EventType.SESSION_ARCHIVED,
            EventType.SESSION_REOPENED,
            EventType.PLAYER_REMOVED
    );

    @Override
    public Set<String> supportedEventTypes() {
        return Set.of(
                EventType.SESSION_CREATED,
                EventType.PLAYER_ADDED,
                EventType.BUY_IN_ADDED,
                EventType.PLAYER_REMOVED,
                EventType.CASH_OUT_ADDED,
                EventType.HAND_PLAYED,
                EventType.SESSION_ARCHIVED,
                EventType.SESSION_CLOSED,
                EventType.SESSION_REOPENED
        );
    }

    @Override
    public void handle(DomainEvent event) {
        if (MUTATING_EVENTS.contains(event.getEventType())) {
            log.debug("Broadcasting live update for session {} event {}", event.getAggregateId(), event.getEventType());
            
            redisTemplate.convertAndSend(REDIS_CHANNEL,
                    event.getAggregateId() + "\n" + event.getEventType() + "\n" + event.getVersion());
        }
    }
}
