package com.example.backend.aggregate;

import com.example.backend.commands.*;
import com.example.backend.events.DomainEvent;
import com.example.backend.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class SessionAggregateTest {

    private static final String HOST_ID = "host-123";
    private static final String OTHER_USER_ID = "other-456";

    @Nested
    @DisplayName("CreateSessionCommand")
    class CreateSession {

        @Test
        @DisplayName("raises SessionCreated with the correct payload on a fresh aggregate")
        void createsSessionSuccessfully() {
            SessionAggregate aggregate = new SessionAggregate();
            String sessionId = UUID.randomUUID().toString();

            aggregate.handle(new CreateSessionCommand(sessionId, HOST_ID, "Friday Night", "High stakes"));

            List<DomainEvent> events = aggregate.getUncommittedEvents();
            assertThat(events).hasSize(1);

            DomainEvent event = events.get(0);
            assertThat(event.getEventType()).isEqualTo("SessionCreated");
            assertThat(event.getUserId()).isEqualTo(HOST_ID);
            assertThat(event.getPayload())
                    .containsEntry("name", "Friday Night")
                    .containsEntry("description", "High stakes")
                    .containsEntry("hostId", HOST_ID);
            assertThat(event.getVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("rejects a second CreateSessionCommand on an aggregate that already has an id")
        void rejectsDoubleCreation() {
            SessionAggregate aggregate = new SessionAggregate();
            String sessionId = UUID.randomUUID().toString();
            aggregate.handle(new CreateSessionCommand(sessionId, HOST_ID, "Friday Night", "High stakes"));

            assertThatThrownBy(() ->
                    aggregate.handle(new CreateSessionCommand(sessionId, HOST_ID, "Again", "desc")))
                    .isInstanceOf(InvalidSessionStateException.class);
        }
    }

    static class AggregateFixture {
        final List<DomainEvent> history = new ArrayList<>();
        SessionAggregate aggregate;

        AggregateFixture() {
            this.aggregate = new SessionAggregate();
        }

        void handle(Object command) {
            dispatch(aggregate, command);
            history.addAll(aggregate.getUncommittedEvents());
            aggregate = SessionAggregate.rehydrate(history);
        }

        void handleExpectingThrow(Object command, Class<? extends Throwable> expectedType) {
            SessionAggregate before = SessionAggregate.rehydrate(history);
            assertThatThrownBy(() -> dispatch(before, command)).isInstanceOf(expectedType);
        }

        String lastPayloadValue(String key) {
            DomainEvent last = history.get(history.size() - 1);
            return (String) last.getPayload().get(key);
        }

        DomainEvent lastEvent() {
            return history.get(history.size() - 1);
        }

        private static void dispatch(SessionAggregate aggregate, Object command) {
            switch (command) {
                case CreateSessionCommand c -> aggregate.handle(c);
                case AddPlayerCommand c -> aggregate.handle(c);
                case JoinSessionCommand c -> aggregate.handle(c);
                case AddBuyInCommand c -> aggregate.handle(c);
                case AddCashOutCommand c -> aggregate.handle(c);
                case RecordHandCommand c -> aggregate.handle(c);
                case RemovePlayerCommand c -> aggregate.handle(c);
                case LeaveSessionCommand c -> aggregate.handle(c);
                case CloseSessionCommand c -> aggregate.handle(c);
                case ArchiveSessionCommand c -> aggregate.handle(c);
                case ReopenSessionCommand c -> aggregate.handle(c);
                default -> throw new IllegalArgumentException(
                        "Unhandled command type in test fixture: " + command.getClass());
            }
        }
    }

    private AggregateFixture fixture;
    private String sessionId;

    @BeforeEach
    void setUpCreatedSession() {
        sessionId = UUID.randomUUID().toString();
        fixture = new AggregateFixture();
        fixture.handle(new CreateSessionCommand(sessionId, HOST_ID, "Test Session", "desc"));
    }

    @Nested
    @DisplayName("AddPlayerCommand")
    class AddPlayer {

        @Test
        @DisplayName("adds a guest player and generates a playerId")
        void addsGuestPlayer() {
            fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, null, "Raj"));

            DomainEvent event = fixture.lastEvent();
            assertThat(event.getEventType()).isEqualTo("PlayerAdded");
            assertThat(event.getPayload()).containsEntry("displayName", "Raj");
            assertThat(event.getPayload().get("playerId")).isNotNull();
            assertThat(event.getVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("rejects a non-host trying to add a player")
        void rejectsNonHost() {
            fixture.handleExpectingThrow(
                    new AddPlayerCommand(sessionId, OTHER_USER_ID, null, "Raj"),
                    UnauthorizedActionException.class);
        }

        @Test
        @DisplayName("rejects adding a player to a session that doesn't exist")
        void rejectsWhenSessionMissing() {
            AggregateFixture blank = new AggregateFixture();
            blank.handleExpectingThrow(
                    new AddPlayerCommand(UUID.randomUUID().toString(), HOST_ID, null, "Raj"),
                    SessionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("JoinSessionCommand")
    class JoinSession {
        @Test
        @DisplayName("joins an active session as a linked account player")
        void joinsSession() {
            fixture.handle(new JoinSessionCommand(sessionId, "player-user", "PlayerName"));
            assertThat(fixture.lastEvent().getEventType()).isEqualTo("PlayerAdded");
            assertThat(fixture.lastEvent().getPayload())
                    .containsEntry("linkedUserId", "player-user")
                    .containsEntry("displayName", "PlayerName");
        }

        @Test
        @DisplayName("rejects joining a closed session")
        void rejectsClosedSession() {
            fixture.handle(new CloseSessionCommand(sessionId, HOST_ID));
            fixture.handleExpectingThrow(new JoinSessionCommand(sessionId, "player-user", "PlayerName"),
                    InvalidSessionStateException.class);
        }
    }

    @Nested
    @DisplayName("AddBuyInCommand")
    class AddBuyIn {

        private String playerId;

        @BeforeEach
        void addPlayer() {
            fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, null, "Raj"));
            playerId = fixture.lastPayloadValue("playerId");
        }

        @Test
        @DisplayName("records buy-ins for multiple players in one sequential event")
        void recordsMultiplePlayerBuyIns() {
            fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, null, "Priya"));
            String secondPlayerId = fixture.lastPayloadValue("playerId");
            fixture.handle(new AddBuyInCommand(sessionId, HOST_ID, Map.of(
                    playerId, new BigDecimal("500"),
                    secondPlayerId, new BigDecimal("250")
            )));

            DomainEvent event = fixture.lastEvent();
            assertThat(event.getEventType()).isEqualTo("BuyInAdded");
            assertThat(event.getVersion()).isEqualTo(4L);
            assertThat(event.getPayload()).containsEntry("deltas", Map.of(
                    playerId, new BigDecimal("500"),
                    secondPlayerId, new BigDecimal("250")
            ));
            assertThat(SessionAggregate.rehydrate(fixture.history).getVersion()).isEqualTo(4L);
        }

        @Test
        @DisplayName("rejects zero and negative buy-in amounts")
        void rejectsNonPositiveAmounts() {
            fixture.handleExpectingThrow(new AddBuyInCommand(sessionId, HOST_ID, Map.of(
                    playerId, BigDecimal.ZERO)), ValidationException.class);
            fixture.handleExpectingThrow(new AddBuyInCommand(sessionId, HOST_ID, Map.of(
                    playerId, new BigDecimal("-1"))), ValidationException.class);
        }

        @Test
        @DisplayName("rejects a buy-in for an unknown player")
        void rejectsUnknownPlayer() {
            fixture.handleExpectingThrow(new AddBuyInCommand(sessionId, HOST_ID, Map.of(
                    "unknown-player", new BigDecimal("100"))), ValidationException.class);
        }

        @Test
        @DisplayName("rejects a non-host buy-in")
        void rejectsNonHost() {
            fixture.handleExpectingThrow(new AddBuyInCommand(sessionId, OTHER_USER_ID, Map.of(
                    playerId, new BigDecimal("100"))), UnauthorizedActionException.class);
        }

        @Test
        @DisplayName("rejects buy-in for a removed player")
        void rejectsInactivePlayer() {
            fixture.handle(new RemovePlayerCommand(sessionId, HOST_ID, playerId));
            fixture.handleExpectingThrow(new AddBuyInCommand(sessionId, HOST_ID, Map.of(
                    playerId, new BigDecimal("100"))), ValidationException.class);
        }

    }

    @Test
    @DisplayName("event versions remain sequential after a multi-player buy-in")
    void eventVersionsRemainSequential() {
        fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, null, "Raj"));
        String firstPlayer = fixture.lastPayloadValue("playerId");
        fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, null, "Priya"));
        String secondPlayer = fixture.lastPayloadValue("playerId");
        fixture.handle(new AddBuyInCommand(sessionId, HOST_ID, Map.of(
                firstPlayer, new BigDecimal("100"),
                secondPlayer, new BigDecimal("100")
        )));
        fixture.handle(new CloseSessionCommand(sessionId, HOST_ID));

        assertThat(fixture.history).extracting(DomainEvent::getVersion)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Nested
    @DisplayName("AddCashOutCommand")
    class AddCashOut {

        private String playerId;

        @Test
        @DisplayName("allows cashing out up to the current chip stack")
        void allowsCashOutWithinStack() {
            fixture.handle(new AddCashOutCommand(sessionId, HOST_ID, playerId, new BigDecimal("500")));
            assertThat(fixture.lastEvent().getEventType()).isEqualTo("CashOutAdded");
        }

        @Test
        @DisplayName("rejects cashing out more than the current chip stack")
        void rejectsCashOutExceedingStack() {
            fixture.handleExpectingThrow(
                    new AddCashOutCommand(sessionId, HOST_ID, playerId, new BigDecimal("501")),
                    ValidationException.class);
        }
    }

    @Nested
    @DisplayName("RecordHandCommand")
    class RecordHand {

        private String playerAId;
        private String playerBId;


        @Test
        @DisplayName("accepts a hand whose deltas sum to zero")
        void acceptsBalancedHand() {
            Map<String, BigDecimal> deltas = Map.of(
                    playerAId, new BigDecimal("200"),
                    playerBId, new BigDecimal("-200")
            );

            fixture.handle(new RecordHandCommand(sessionId, HOST_ID, deltas));
            assertThat(fixture.lastEvent().getEventType()).isEqualTo("HandPlayed");
        }

        @Test
        @DisplayName("rejects a hand whose deltas do not sum to zero")
        void rejectsUnbalancedHand() {
            Map<String, BigDecimal> deltas = Map.of(
                    playerAId, new BigDecimal("200"),
                    playerBId, new BigDecimal("-100") // does not balance
            );

            fixture.handleExpectingThrow(
                    new RecordHandCommand(sessionId, HOST_ID, deltas),
                    ValidationException.class);
        }
    }

    @Nested
    @DisplayName("Session lifecycle transitions")
    class Lifecycle {

        @Test
        @DisplayName("ACTIVE -> CLOSED is allowed")
        void activeToClosedAllowed() {
            fixture.handle(new CloseSessionCommand(sessionId, HOST_ID));
            assertThat(fixture.lastEvent().getEventType()).isEqualTo("SessionClosed");
        }

        @Test
        @DisplayName("CLOSED -> ARCHIVED is rejected")
        void closedToArchivedRejected() {
            fixture.handle(new CloseSessionCommand(sessionId, HOST_ID));
            fixture.handleExpectingThrow(
                    new ArchiveSessionCommand(sessionId, HOST_ID),
                    InvalidSessionStateException.class);
        }

        @Test
        @DisplayName("CLOSED -> ACTIVE (reopen) is allowed")
        void closedToActiveAllowed() {
            fixture.handle(new CloseSessionCommand(sessionId, HOST_ID));
            fixture.handle(new ReopenSessionCommand(sessionId, HOST_ID));
            assertThat(fixture.lastEvent().getEventType()).isEqualTo("SessionReopened");
        }

        @Test
        @DisplayName("ARCHIVED -> CLOSED is rejected (must go through ACTIVE)")
        void archivedToClosedRejected() {
            fixture.handle(new ArchiveSessionCommand(sessionId, HOST_ID));
            fixture.handleExpectingThrow(
                    new CloseSessionCommand(sessionId, HOST_ID),
                    InvalidSessionStateException.class);
        }
    }

    @Nested
    @DisplayName("RemovePlayerCommand")
    class RemovePlayer {

        private String playerId;

        @BeforeEach
        void addPlayer() {
            fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, null, "Raj"));
            playerId = fixture.lastPayloadValue("playerId");
        }

        @Test
        @DisplayName("removes an active player")
        void removesActivePlayer() {
            fixture.handle(new RemovePlayerCommand(sessionId, HOST_ID, playerId));
            assertThat(fixture.lastEvent().getEventType()).isEqualTo("PlayerRemoved");
            assertThat(fixture.aggregate.getPlayers().get(playerId).status().name()).isEqualTo("INACTIVE");
        }

        @Test
        @DisplayName("rejects removing an already-removed player")
        void rejectsDoubleRemoval() {
            fixture.handle(new RemovePlayerCommand(sessionId, HOST_ID, playerId));
            fixture.handleExpectingThrow(
                    new RemovePlayerCommand(sessionId, HOST_ID, playerId),
                    InvalidSessionStateException.class);
        }
    }

    @Nested
    @DisplayName("LeaveSessionCommand")
    class LeaveSession {
        @Test
        @DisplayName("allows a linked player to leave with an empty stack")
        void leavesWithEmptyStack() {
            fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, "player-user", "Player"));
            String playerId = fixture.lastPayloadValue("playerId");

            fixture.handle(new LeaveSessionCommand(sessionId, "player-user"));

            assertThat(fixture.lastEvent().getEventType()).isEqualTo("PlayerRemoved");
            assertThat(fixture.lastEvent().getPayload()).containsEntry("playerId", playerId);
            assertThat(fixture.aggregate.getPlayers().get(playerId).status().name()).isEqualTo("INACTIVE");
        }

        @Test
        @DisplayName("rejects leaving while the player's stack is nonempty")
        void rejectsNonemptyStack() {
            fixture.handle(new AddPlayerCommand(sessionId, HOST_ID, "player-user", "Player"));
            String playerId = fixture.lastPayloadValue("playerId");
            fixture.handle(new AddBuyInCommand(sessionId, HOST_ID, Map.of(playerId, new BigDecimal("25"))));

            assertThatThrownBy(() -> fixture.handle(new LeaveSessionCommand(sessionId, "player-user")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("stack is not empty");
        }
    }
}
