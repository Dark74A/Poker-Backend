package com.example.backend.aggregate;

import com.example.backend.commands.*;
import com.example.backend.events.DomainEvent;
import com.example.backend.events.EventType;
import com.example.backend.exception.*;
import com.example.backend.model.Player;
import com.example.backend.model.PlayerStatus;
import com.example.backend.model.SessionStatus;

import lombok.Getter;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static com.example.backend.events.EventType.*;

@Getter
public class SessionAggregate {

    private String id;
    private SessionStatus status;
    private String hostId;
    private String name;
    private String description;
    private String inviteCode;

    private Map<String, Player> players = new HashMap<>();
    private long version;
    private long baseVersion;
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

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

    private static final Map<SessionStatus, Set<SessionStatus>> ALLOWED_TRANSITIONS = Map.of(
            SessionStatus.ACTIVE, Set.of(
                    SessionStatus.CLOSED,
                    SessionStatus.ARCHIVED
            ),
            SessionStatus.CLOSED, Set.of(
                    SessionStatus.ACTIVE
            ),
            SessionStatus.ARCHIVED, Set.of(
                    SessionStatus.ACTIVE
            )
    );

    private void requireTransitionAllowed(SessionStatus target) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new InvalidSessionStateException(
                    "Cannot transition from " + status + " to " + target
            );
        }
    }

    public static SessionAggregate rehydrate(List<DomainEvent> history) {
        SessionAggregate aggregate = new SessionAggregate();
        for (DomainEvent event : history) {
            if (event.getVersion() != aggregate.version + 1) {
                throw new IllegalStateException("Non-sequential event version " + event.getVersion());
            }
            aggregate.apply(event);
        }
        aggregate.baseVersion = aggregate.version;

        return aggregate;
    }

    public void handle(CreateSessionCommand cmd) {

        if (id != null) {
            throw new InvalidSessionStateException("Session Already Exists");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("hostId", cmd.hostId());
        payload.put("name", cmd.name());
        payload.put("description", cmd.description());
        payload.put("inviteCode", cmd.inviteCode());
        raise(
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        cmd.sessionId(),
                        "SESSION",
                        version + 1,
                        Instant.now(),
                        SESSION_CREATED,
                        cmd.hostId(),
                        payload,
                        Map.of()
                )
        );
    }

    public String handle(AddPlayerCommand cmd) {

        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("You are not the host");
        }

        if (status != SessionStatus.ACTIVE) {
            throw new InvalidSessionStateException("Session is " + status.name().toLowerCase() + ".");
        }

        if (cmd.linkedUserId() != null) {
            Player existingPlayer = players.values().stream()
                    .filter(p -> cmd.linkedUserId().equals(p.userId()))
                    .findFirst()
                    .orElse(null);
            if (existingPlayer != null && existingPlayer.status() == PlayerStatus.ACTIVE) {
                throw new InvalidSessionStateException("This user is already a player in this session.");
            }
            if (existingPlayer != null) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("playerId", existingPlayer.playerId());
                payload.put("linkedUserId", existingPlayer.userId());
                payload.put("displayName", existingPlayer.displayName());
                payload.put("rejoin", true);
                raise(new DomainEvent(UUID.randomUUID().toString(), id, "SESSION", version + 1,
                        Instant.now(), PLAYER_ADDED, cmd.hostId(), payload, Map.of()));
                return existingPlayer.playerId();
            }
        }

        String playerId = UUID.randomUUID().toString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerId);
        payload.put("linkedUserId", cmd.linkedUserId());
        payload.put("displayName", cmd.displayName());

        raise(
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        id,
                        "SESSION",
                        version + 1,
                        Instant.now(),
                        PLAYER_ADDED,
                        cmd.hostId(),
                        payload,
                        Map.of()
                )
        );

        return playerId;
    }

    public String handle(JoinSessionCommand cmd) {
        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }
        if (status != SessionStatus.ACTIVE) {
            throw new InvalidSessionStateException("Session is " + status.name().toLowerCase() + ".");
        }

        Player existingPlayer = players.values().stream()
                .filter(player -> cmd.userId().equals(player.userId()))
                .findFirst()
                .orElse(null);
        if (existingPlayer != null && existingPlayer.status() == PlayerStatus.ACTIVE) {
            throw new InvalidSessionStateException("You are already a player in this session.");
        }

        String playerId = existingPlayer == null ? UUID.randomUUID().toString() : existingPlayer.playerId();
        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerId);
        payload.put("linkedUserId", cmd.userId());
        payload.put("displayName", existingPlayer == null ? cmd.username() : existingPlayer.displayName());
        if (existingPlayer != null) payload.put("rejoin", true);

        raise(new DomainEvent(UUID.randomUUID().toString(), id, "SESSION", version + 1,
                Instant.now(), PLAYER_ADDED, cmd.userId(), payload, Map.of()));
        return playerId;
    }

    public String handle(AddBuyInCommand cmd) {

        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("You are not the host");
        }

        if (status != SessionStatus.ACTIVE) {
            throw new InvalidSessionStateException("Session is " + status.name().toLowerCase() + ".");

        }

        for (Map.Entry<String, BigDecimal> entry : cmd.deltas().entrySet()) {
            String playerId = entry.getKey();

            Player player = players.get(playerId);

            if (player == null) {
                throw new ValidationException("Player does not exist in this session: " + playerId);
            }

            if (player.status() == PlayerStatus.INACTIVE) {
                throw new ValidationException(
                        "Cannot add buy-in for an inactive player. Please add the player back to the table first.");
            }
            
            BigDecimal delta = entry.getValue();
            if (delta == null || delta.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException("Buy-in amounts must be greater than zero.");
            }

        }


        String buyInId = UUID.randomUUID().toString();


        raise(new DomainEvent(
                UUID.randomUUID().toString(),
                id,
                "SESSION",
                version + 1,
                Instant.now(),
                EventType.BUY_IN_ADDED,
                cmd.hostId(),
                Map.of(
                        "buyInId", buyInId,
                        "deltas", cmd.deltas()
                ),
                Map.of()
        ));

        return buyInId;
    }
    
    public String handle(RecordHandCommand cmd) {

        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("You are not the host");
        }

        if (status != SessionStatus.ACTIVE) {
            throw new InvalidSessionStateException("Session is " + status.name().toLowerCase() + ".");
        }

        if (cmd.deltas().values().stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("Every player needs an amount.");
        }

        BigDecimal sum = cmd.deltas().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (sum.compareTo(BigDecimal.ZERO) != 0) {
            throw new ValidationException("Hand deltas must sum to zero — chips can only move between players, not appear or vanish.");
        }

        for (Map.Entry<String, BigDecimal> entry : cmd.deltas().entrySet()) {
            String playerId = entry.getKey();
            BigDecimal delta = entry.getValue();

            Player player = players.get(playerId);

            if (player == null) {
                throw new ValidationException("Player does not exist in this session: " + playerId);
            }

            if (player.status() == PlayerStatus.INACTIVE) {
                throw new ValidationException(
                        "Cannot record a hand involving an inactive player. Please add the player back to the table first.");
            }

            if (player.chipStack().add(delta).compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException("Player cannot lose more chips than their current stack.");
            }
        }

        String handId = UUID.randomUUID().toString();


        raise(new DomainEvent(
                UUID.randomUUID().toString(),
                id,
                "SESSION",
                version + 1,
                Instant.now(),
                EventType.HAND_PLAYED,
                cmd.hostId(),
                Map.of(
                        "handId", handId,
                        "deltas", cmd.deltas()
                ),
                Map.of()
        ));

        return handId;
    }

    public String handle(AddCashOutCommand cmd) {
        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("You are not the host");
        }

        if (status != SessionStatus.ACTIVE) {
            throw new InvalidSessionStateException("Session is " + status.name().toLowerCase() + ".");
        }

        Player player = players.get(cmd.playerId());

        if (player == null) {
            throw new PlayerNotFoundException("Player " + cmd.playerId() + " not found in this session.");
        }

        if (player.status() == PlayerStatus.INACTIVE) {
            throw new InvalidSessionStateException(
                    "Player has been removed from this session."
            );
        }

        if (cmd.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Cashout must be positive.");
        }

        if (cmd.amount().compareTo(player.chipStack()) > 0) {
            throw new ValidationException("Cannot cash out more than the player's current chip stack.");
        }

        Map<String, Object> payload = new HashMap<>();
        String cashOutId = UUID.randomUUID().toString();

        payload.put("cashOutId", cashOutId);
        payload.put("playerId", cmd.playerId());
        payload.put("amount", cmd.amount());

        raise(
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        id,
                        "SESSION",
                        0,
                        Instant.now(),
                        EventType.CASH_OUT_ADDED,
                        cmd.hostId(),
                        payload,
                        Map.of()
                )
        );

        return cashOutId;
    }

    public void handle(CloseSessionCommand cmd) {

        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("Only the host can close the session.");
        }
        requireTransitionAllowed(SessionStatus.CLOSED);

        Map<String, Object> payload = Map.of("previousStatus", status.name());
        raise(
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        id,
                        "SESSION",
                        0,
                        Instant.now(),
                        EventType.SESSION_CLOSED,
                        cmd.hostId(),
                        payload,
                        Map.of()
                )
        );

    }

    public void handle(ArchiveSessionCommand cmd) {

        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("Only the host can archive the session.");
        }

        requireTransitionAllowed(SessionStatus.ARCHIVED);

        Map<String, Object> payload = Map.of("previousStatus", status.name());
        raise(
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        id,
                        "SESSION",
                        0,
                        Instant.now(),
                        EventType.SESSION_ARCHIVED,
                        cmd.hostId(),
                        payload,
                        Map.of()
                )
        );

    }

    public void handle(ReopenSessionCommand cmd) {

        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("Only the host can reopen the session.");
        }

        requireTransitionAllowed(SessionStatus.ACTIVE);

        Map<String, Object> payload = Map.of("previousStatus", status.name());
        raise(
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        id,
                        "SESSION",
                        0,
                        Instant.now(),
                        EventType.SESSION_REOPENED,
                        cmd.hostId(),
                        payload,
                        Map.of()
                )
        );

    }

    public void handle(RemovePlayerCommand cmd) {

        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }

        if (!hostId.equals(cmd.hostId())) {
            throw new UnauthorizedActionException("Only the host can remove a player.");
        }

        if (status != SessionStatus.ACTIVE) {
            throw new InvalidSessionStateException("Session is " + status.name().toLowerCase() + ".");
        }

        Player player = players.get(cmd.playerId());
        if (player == null) {
            throw new PlayerNotFoundException("Player " + cmd.playerId() + " not found in this session.");
        }

        if (player.status() == PlayerStatus.INACTIVE) {
            throw new InvalidSessionStateException("Player has already been removed from this session.");
        }

        if (player.chipStack().compareTo(BigDecimal.ZERO) != 0) {
            throw new ValidationException(
                    "Cannot remove player while they still have chips. Please record their cash-out before removing them.");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", cmd.playerId());

        raise(
                new DomainEvent(
                        UUID.randomUUID().toString(),
                        id,
                        "SESSION",
                        0,
                        Instant.now(),
                        EventType.PLAYER_REMOVED,
                        cmd.hostId(),
                        payload,
                        Map.of()
                )
        );
    }

    public void handle(LeaveSessionCommand cmd) {
        if (id == null) {
            throw new SessionNotFoundException("Session does not exist.");
        }
        if (status != SessionStatus.ACTIVE) {
            throw new InvalidSessionStateException("Session is " + status.name().toLowerCase() + ".");
        }

        Player player = players.values().stream()
                .filter(candidate -> cmd.userId().equals(candidate.userId()))
                .findFirst()
                .orElseThrow(() -> new PlayerNotFoundException("You are not a player in this session."));
        if (player.status() == PlayerStatus.INACTIVE) {
            throw new InvalidSessionStateException("You have already left this session.");
        }
        if (player.chipStack().compareTo(BigDecimal.ZERO) != 0) {
            throw new ValidationException("You cannot leave while your stack is not empty. Cash out first.");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", player.playerId());
        raise(new DomainEvent(UUID.randomUUID().toString(), id, "SESSION", 0, Instant.now(),
                EventType.PLAYER_REMOVED, cmd.userId(), payload, Map.of()));
    }

    private void raise(DomainEvent event) {
        apply(event);
        uncommittedEvents.add(event);
    }

    private void apply(DomainEvent event) {

        switch (event.getEventType()) {

            case SESSION_CREATED -> {
                this.id = event.getAggregateId();
                this.hostId = (String) event.getPayload().get("hostId");
                this.status = SessionStatus.ACTIVE;
                this.name = (String) event.getPayload().get("name");
                this.description = (String) event.getPayload().get("description");
                this.inviteCode = (String) event.getPayload().get("inviteCode");
            }

            case PLAYER_ADDED -> {
                Map<String, Object> payload = event.getPayload();
                String playerId = (String) payload.get("playerId");
                Player previous = players.get(playerId);
                if (previous != null && Boolean.TRUE.equals(payload.get("rejoin"))) {
                    players.put(playerId, new Player(previous.playerId(), previous.userId(), previous.displayName(),
                            previous.totalBuyIn(), previous.totalCashOut(), previous.chipStack(), PlayerStatus.ACTIVE, previous.notes()));
                    break;
                }
                Player player = new Player(
                        playerId,
                        (String) payload.get("linkedUserId"),
                        (String) payload.get("displayName"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        PlayerStatus.ACTIVE,
                        ""
                );
                players.put(player.playerId(), player);
            }

            case BUY_IN_ADDED -> {

                @SuppressWarnings("unchecked")
                Map<String, Object> rawDeltas = (Map<String, Object>) event.getPayload().get("deltas");

                for (Map.Entry<String, Object> entry : rawDeltas.entrySet()) {
                    String playerId = entry.getKey();
                    BigDecimal delta = toBigDecimal(entry.getValue());

                    Player player = players.get(playerId);

                    if (player == null) {
                        throw new IllegalStateException(
                                "Player " + playerId + " not found in session");
                    }

                    Player updatedPlayer = new Player(
                            player.playerId(),
                            player.userId(),
                            player.displayName(),
                            player.totalBuyIn().add(delta),
                            player.totalCashOut(),
                            player.chipStack().add(delta),
                            player.status(),
                            player.notes());

                    players.put(playerId, updatedPlayer);
                
                }
            }

            case HAND_PLAYED -> {

                @SuppressWarnings("unchecked")
                Map<String, Object> rawDeltas =
                        (Map<String, Object>) event.getPayload().get("deltas");

                for (Map.Entry<String, Object> entry : rawDeltas.entrySet()) {
                    String playerId = entry.getKey();
                    BigDecimal delta = toBigDecimal(entry.getValue());

                    Player player = players.get(playerId);

                    if (player == null) {
                        throw new IllegalStateException(
                                "Player " + playerId + " not found in session"
                        );
                    }

                    Player updatedPlayer = new Player(
                            player.playerId(),
                            player.userId(),
                            player.displayName(),
                            player.totalBuyIn(),
                            player.totalCashOut(),
                            player.chipStack().add(delta),
                            player.status(),
                            player.notes()
                    );

                    players.put(playerId, updatedPlayer);
                }
            }

            case CASH_OUT_ADDED -> {
                Map<String, Object> payload = event.getPayload();
                String playerId = (String) payload.get("playerId");
                BigDecimal amount = toBigDecimal(payload.get("amount"));

                Player player = players.get(playerId);
                Player updatedPlayer = new Player(
                        player.playerId(),
                        player.userId(),
                        player.displayName(),
                        player.totalBuyIn(),
                        player.totalCashOut().add(amount),
                        player.chipStack().subtract(amount),
                        player.status(),
                        player.notes()
                );

                players.put(playerId, updatedPlayer);
            }

            case SESSION_CLOSED -> this.status = SessionStatus.CLOSED;

            case SESSION_ARCHIVED -> this.status = SessionStatus.ARCHIVED;

            case SESSION_REOPENED -> this.status = SessionStatus.ACTIVE;

            case PLAYER_REMOVED -> {
                Map<String, Object> payload = event.getPayload();
                String playerId = (String) payload.get("playerId");

                Player player = players.get(playerId);
                Player updatedPlayer = new Player(
                        player.playerId(),
                        player.userId(),
                        player.displayName(),
                        player.totalBuyIn(),
                        player.totalCashOut(),
                        player.chipStack(),
                        PlayerStatus.INACTIVE,
                        player.notes()
                );

                players.put(playerId, updatedPlayer);
            }

            default ->
                    throw new IllegalStateException("Unknown event type " + event.getEventType());

        }
        this.version = event.getVersion();

    }

    public List<DomainEvent> getUncommittedEvents() {
        return List.copyOf(uncommittedEvents);
    }

    public void markEventsAsCommitted() {
        uncommittedEvents.clear();
        baseVersion = version;
    }
}
