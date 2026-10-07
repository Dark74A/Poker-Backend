package com.example.backend.projections;

import com.example.backend.events.DomainEvent;
import com.example.backend.events.EventHandler;
import com.example.backend.events.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class LiveUpdateBroadcaster implements EventHandler {

    private final SimpMessagingTemplate messagingTemplate;

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
            
            Map<String, Object> payload = Map.of(
                    "type", event.getEventType(),
                    "version", event.getVersion()
            );
            
            String destination = "/topic/sessions/" + event.getAggregateId();

            messagingTemplate.convertAndSend(destination, (Object) payload);
        }
    }
}
