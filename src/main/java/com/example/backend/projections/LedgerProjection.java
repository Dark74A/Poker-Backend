package com.example.backend.projections;

import com.example.backend.events.DomainEvent;
import com.example.backend.events.EventHandler;
import com.example.backend.events.EventType;
import com.example.backend.model.PlayerStatus;
import com.example.backend.model.SessionStatus;
import com.example.backend.repositories.SessionProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerProjection implements EventHandler {

    private final MongoTemplate mongoTemplate;
    private final SessionProjectionRepository sessionProjectionRepository;

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Decimal128 d128) {
            return d128.bigDecimalValue();
        }
        if (value == null) {
            return null;
        }
        throw new IllegalStateException("Unexpected type for BigDecimal field: " + value.getClass());
    }

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
        switch (event.getEventType()) {
            case EventType.SESSION_CREATED -> handleSessionCreated(event);
            case EventType.PLAYER_ADDED -> handlePlayerAdded(event);
            case EventType.BUY_IN_ADDED -> handleBuyInAdded(event);
            case EventType.CASH_OUT_ADDED -> handleCashOutAdded(event);
            case EventType.PLAYER_REMOVED -> handlePlayerRemoved(event);
            case EventType.HAND_PLAYED -> handleHandPlayed(event);
            case EventType.SESSION_CLOSED -> handleSessionStatusChange(event, "CLOSED");
            case EventType.SESSION_ARCHIVED -> handleSessionStatusChange(event, "ARCHIVED");
            case EventType.SESSION_REOPENED -> handleSessionStatusChange(event, "ACTIVE");
            default -> throw new IllegalStateException(
                    "Unsupported event: " + event.getEventType());
        }
    }

    private void handleSessionStatusChange(DomainEvent event, String newStatus) {
        Query query = Query.query(
            Criteria.where("_id").is(event.getAggregateId())
                    .and("lastAppliedVersion").lt(event.getVersion())
        );
        Update update = new Update()
            .set("status", newStatus)
            .set("updatedAt", event.getTimestamp())
            .set("lastAppliedVersion", event.getVersion());

        var result = mongoTemplate.updateFirst(query, update, SessionProjection.class);
        if (result.getModifiedCount() == 0) {
          log.warn("{} version {} for session {} not applied", event.getEventType(), event.getVersion(), event.getAggregateId());
        }
    }

    private void handleSessionCreated(DomainEvent event) {
        Map<String, Object> payload = event.getPayload();

        Query query = Query.query(Criteria.where("_id").is(event.getAggregateId()));

        Update update = new Update()
                .setOnInsert("sessionId", event.getAggregateId())
                .setOnInsert("sessionName", payload.get("name"))
                .setOnInsert("hostId", payload.get("hostId"))
                .setOnInsert("status", SessionStatus.ACTIVE.name())
                .setOnInsert("createdAt", event.getTimestamp())
                .setOnInsert("updatedAt", event.getTimestamp())
                .setOnInsert("lastAppliedVersion", event.getVersion())
                .setOnInsert("players", java.util.List.of());
        if (payload.get("inviteCode") != null) {
            update.setOnInsert("inviteCode", payload.get("inviteCode"));
        }

        var result = mongoTemplate.upsert(query, update, SessionProjection.class);

        if (result.getUpsertedId() == null && result.getModifiedCount() == 0) {
            log.info("SessionCreated for {} already applied, skipping", event.getAggregateId());
        }
    }

    private void handlePlayerAdded(DomainEvent event) {
        Map<String, Object> payload = event.getPayload();

        PlayerProjection player = PlayerProjection.builder()
                .playerId((String) payload.get("playerId"))
                .displayName((String) payload.get("displayName"))
                .userId((String) payload.get("linkedUserId"))
                .totalBuyIn(BigDecimal.ZERO)
                .totalCashOut(BigDecimal.ZERO)
                .status(PlayerStatus.ACTIVE.name())
                .build();

        Update update = new Update()
                .set("updatedAt", event.getTimestamp())
                .set("lastAppliedVersion", event.getVersion());

        Query query = Query.query(
                Criteria.where("_id").is(event.getAggregateId())
                        .and("lastAppliedVersion").lt(event.getVersion())
        );
        if (Boolean.TRUE.equals(payload.get("rejoin"))) {
            update.set("players.$[player].status", PlayerStatus.ACTIVE.name())
                    .filterArray(Criteria.where("player.playerId").is(payload.get("playerId")));
        } else {
            update.push("players", player);
        }

        var result = mongoTemplate.updateFirst(query, update, SessionProjection.class);

        if (result.getModifiedCount() == 0) {
            log.warn(
                    "PlayerAdded version {} for session {} not applied — " +
                            "either already processed, or projection missing (out-of-order delivery)",
                    event.getVersion(), event.getAggregateId()
            );
        }
    }

    private void handleBuyInAdded(DomainEvent event) {

        @SuppressWarnings("unchecked")
        Map<String, Object> rawDeltas =
                (Map<String, Object>) event.getPayload().get("deltas");

        Map<String, BigDecimal> deltas = rawDeltas.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> toBigDecimal(e.getValue())
                ));

        applyPlayerDeltas(event, deltas, true);
    }

    private void handleCashOutAdded(DomainEvent event) {

        Map<String, Object> payload = event.getPayload();
        String playerId = (String) payload.get("playerId");
        BigDecimal amount = toBigDecimal(payload.get("amount"));

        Query query = Query.query(
                Criteria.where("_id").is(event.getAggregateId())
                        .and("lastAppliedVersion").lt(event.getVersion())
                        .and("players.playerId").is(playerId)
        );

        Update update = new Update()
                .inc("players.$.totalCashOut", amount)
                .inc("players.$.chipStack", amount.negate())
                .set("updatedAt", event.getTimestamp())
                .set("lastAppliedVersion", event.getVersion());

        var result =
                mongoTemplate.updateFirst(
                        query,
                        update,
                        SessionProjection.class
                );

        if (result.getModifiedCount() == 0) {

            log.warn(
                    "CASH_OUT_ADDED version {} for session {} ignored. " +
                            "Already processed, player missing, or event out of order.",
                    event.getVersion(),
                    event.getAggregateId()
            );
        }
    }

    private void handleHandPlayed(DomainEvent event) {

        @SuppressWarnings("unchecked")
        Map<String, Object> rawDeltas =
                (Map<String, Object>) event.getPayload().get("deltas");

        Map<String, BigDecimal> deltas = rawDeltas.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> toBigDecimal(e.getValue())
                ));

        applyPlayerDeltas(event, deltas, false);
    }

    private void applyPlayerDeltas(DomainEvent event, Map<String, BigDecimal> deltas, boolean buyIn) {
        Query query = Query.query(Criteria.where("_id").is(event.getAggregateId())
                .and("lastAppliedVersion").lt(event.getVersion()));
        SessionProjection projection = mongoTemplate.findOne(query, SessionProjection.class);
        if (projection == null) {
            log.warn("{} version {} for session {} is duplicate or out of order", event.getEventType(), event.getVersion(), event.getAggregateId());
            return;
        }
        Map<String, PlayerProjection> byId = projection.getPlayers().stream()
                .collect(Collectors.toMap(PlayerProjection::getPlayerId, player -> player));
        if (!byId.keySet().containsAll(deltas.keySet())) {
            throw new IllegalStateException("Event references a player missing from its projection");
        }
        Update update = new Update().set("updatedAt", event.getTimestamp());
        for (Map.Entry<String, BigDecimal> entry : deltas.entrySet()) {
            PlayerProjection player = byId.get(entry.getKey());
            BigDecimal stack = player.getChipStack().add(entry.getValue());
            update.set("players.$[p" + deltas.keySet().stream().toList().indexOf(entry.getKey()) + "].chipStack", stack);
            update.filterArray(Criteria.where("p" + deltas.keySet().stream().toList().indexOf(entry.getKey()) + ".playerId").is(entry.getKey()));
            if (buyIn) {
                update.set("players.$[p" + deltas.keySet().stream().toList().indexOf(entry.getKey()) + "].totalBuyIn", player.getTotalBuyIn().add(entry.getValue()));
            }
        }
        update.set("lastAppliedVersion", event.getVersion());
        mongoTemplate.updateFirst(query, update, SessionProjection.class);
    }

    private void handlePlayerRemoved(DomainEvent event) {

        Map<String, Object> payload = event.getPayload();
        String playerId = (String) payload.get("playerId");

        Query query = Query.query(
                Criteria.where("_id").is(event.getAggregateId())
                        .and("lastAppliedVersion").lt(event.getVersion())
                        .and("players.playerId").is(playerId)
        );

        Update update = new Update()
                .set("players.$.status", PlayerStatus.INACTIVE.name())
                .set("updatedAt", event.getTimestamp())
                .set("lastAppliedVersion", event.getVersion());

        var result = mongoTemplate.updateFirst(query, update, SessionProjection.class);

        if (result.getModifiedCount() == 0) {
            log.warn(
                    "PlayerRemoved version {} for session {} not applied — " +
                            "already processed, player missing, or out-of-order delivery",
                    event.getVersion(), event.getAggregateId()
            );
        }
    }
}
