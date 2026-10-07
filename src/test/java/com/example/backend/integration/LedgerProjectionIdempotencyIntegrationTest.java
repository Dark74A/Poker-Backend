package com.example.backend.integration;

import com.example.backend.events.DomainEvent;
import com.example.backend.projections.LedgerProjection;
import com.example.backend.repositories.SessionProjectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerProjectionIdempotencyIntegrationTest extends AbstractMongoIntegrationTest {

    @Autowired
    private LedgerProjection ledgerProjection;

    @Autowired
    private SessionProjectionRepository sessionProjectionRepository;

    @Test
    void redeliveringTheSamePlayerAddedEventTwiceOnlyAppliesItOnce() {
        String sessionId = UUID.randomUUID().toString();
        String playerId = UUID.randomUUID().toString();

        DomainEvent sessionCreated = new DomainEvent(
                UUID.randomUUID().toString(), sessionId, "SESSION", 1, Instant.now(),
                "SessionCreated", "host-1",
                Map.of("name", "Idempotency Test", "hostId", "host-1", "description", "desc"),
                Map.of()
        );

        DomainEvent playerAdded = new DomainEvent(
                UUID.randomUUID().toString(), sessionId, "SESSION", 2, Instant.now(),
                "PlayerAdded", "host-1",
                Map.of("playerId", playerId, "displayName", "Raj", "linkedUserId", ""),
                Map.of()
        );

        ledgerProjection.handle(sessionCreated);
        ledgerProjection.handle(playerAdded);
        ledgerProjection.handle(playerAdded);

        var projection = sessionProjectionRepository.findById(sessionId).orElseThrow();

        assertThat(projection.getPlayers())
                .as("the player should appear exactly once, not duplicated by the redelivered event")
                .hasSize(1);
        assertThat(projection.getLastAppliedVersion()).isEqualTo(2L);
    }

    @Test
    void redeliveringABuyInAddedEventTwiceDoesNotDoubleCountTheAmount() {
        String sessionId = UUID.randomUUID().toString();
        String playerId = UUID.randomUUID().toString();

        ledgerProjection.handle(new DomainEvent(
                UUID.randomUUID().toString(), sessionId, "SESSION", 1, Instant.now(),
                "SessionCreated", "host-1",
                Map.of("name", "Idempotency Test 2", "hostId", "host-1", "description", "desc"),
                Map.of()
        ));
        ledgerProjection.handle(new DomainEvent(
                UUID.randomUUID().toString(), sessionId, "SESSION", 2, Instant.now(),
                "PlayerAdded", "host-1",
                Map.of("playerId", playerId, "displayName", "Raj", "linkedUserId", ""),
                Map.of()
        ));

        DomainEvent buyInAdded = new DomainEvent(
                UUID.randomUUID().toString(), sessionId, "SESSION", 3, Instant.now(),
                "BuyInAdded", "host-1",
                Map.of("playerId", playerId, "amount", new BigDecimal("500"), "buyInId", UUID.randomUUID().toString()),
                Map.of()
        );

        ledgerProjection.handle(buyInAdded);
        ledgerProjection.handle(buyInAdded);

        var projection = sessionProjectionRepository.findById(sessionId).orElseThrow();
        var player = projection.getPlayers().get(0);

        assertThat(player.getTotalBuyIn())
                .as("a redelivered BuyInAdded must not be counted twice — this was a real, silent bug class earlier in this project")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    void appliesMultiPlayerBuyInDeltasAndAdvancesVersionOnce() {
        String sessionId = UUID.randomUUID().toString();
        String firstPlayerId = UUID.randomUUID().toString();
        String secondPlayerId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 1, now,
                "SessionCreated", "host-1", Map.of("name", "Multi Buy-In", "hostId", "host-1"), Map.of()));
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 2, now,
                "PlayerAdded", "host-1", Map.of("playerId", firstPlayerId, "displayName", "Raj", "linkedUserId", ""), Map.of()));
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 3, now,
                "PlayerAdded", "host-1", Map.of("playerId", secondPlayerId, "displayName", "Priya", "linkedUserId", ""), Map.of()));

        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 4, now,
                "BuyInAdded", "host-1", Map.of("deltas", Map.of(
                        firstPlayerId, new BigDecimal("500"),
                        secondPlayerId, new BigDecimal("250")
                )), Map.of()));

        var projection = sessionProjectionRepository.findById(sessionId).orElseThrow();
        assertThat(projection.getLastAppliedVersion()).isEqualTo(4L);
        assertThat(projection.getPlayers()).anySatisfy(player -> {
            assertThat(player.getPlayerId()).isEqualTo(firstPlayerId);
            assertThat(player.getTotalBuyIn()).isEqualByComparingTo("500");
            assertThat(player.getChipStack()).isEqualByComparingTo("500");
        });
        assertThat(projection.getPlayers()).anySatisfy(player -> {
            assertThat(player.getPlayerId()).isEqualTo(secondPlayerId);
            assertThat(player.getTotalBuyIn()).isEqualByComparingTo("250");
            assertThat(player.getChipStack()).isEqualByComparingTo("250");
        });
    }

    @Test
    void rejoiningLinkedPlayerReactivatesExistingProjectionWithoutResettingTotals() {
        String sessionId = UUID.randomUUID().toString();
        String playerId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 1, now,
                "SessionCreated", "host-1", Map.of("name", "Rejoin", "hostId", "host-1"), Map.of()));
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 2, now,
                "PlayerAdded", "host-1", Map.of("playerId", playerId, "displayName", "Raj", "linkedUserId", "user-1"), Map.of()));
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 3, now,
                "BuyInAdded", "host-1", Map.of("deltas", Map.of(playerId, new BigDecimal("500"))), Map.of()));
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 4, now,
                "CashOutAdded", "host-1", Map.of("playerId", playerId, "amount", new BigDecimal("500")), Map.of()));
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 5, now,
                "PlayerRemoved", "host-1", Map.of("playerId", playerId), Map.of()));
        ledgerProjection.handle(new DomainEvent(UUID.randomUUID().toString(), sessionId, "SESSION", 6, now,
                "PlayerAdded", "host-1", Map.of("playerId", playerId, "displayName", "Raj", "linkedUserId", "user-1", "rejoin", true), Map.of()));

        var projection = sessionProjectionRepository.findById(sessionId).orElseThrow();
        assertThat(projection.getPlayers()).hasSize(1);
        assertThat(projection.getPlayers().get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(projection.getPlayers().get(0).getTotalBuyIn()).isEqualByComparingTo("500");
        assertThat(projection.getPlayers().get(0).getTotalCashOut()).isEqualByComparingTo("500");
        assertThat(projection.getPlayers().get(0).getChipStack()).isEqualByComparingTo("0");
    }
}
