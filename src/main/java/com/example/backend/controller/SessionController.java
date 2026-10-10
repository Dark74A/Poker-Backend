package com.example.backend.controller;

import com.example.backend.aggregate.SessionAggregate;
import com.example.backend.commands.*;
import com.example.backend.dto.request.*;
import com.example.backend.dto.response.*;
import com.example.backend.events.HistoryEntry;
import com.example.backend.exception.SessionNotFoundException;
import com.example.backend.exception.UnauthorizedActionException;
import com.example.backend.exception.ValidationException;
import com.example.backend.handlers.*;
import com.example.backend.commands.JoinSessionCommand;
import com.example.backend.helpers.CurrentUserProvider;
import com.example.backend.helpers.IdGenerator;
import com.example.backend.helpers.InviteCodeGenerator;
import com.example.backend.projections.PlayerProjection;
import com.example.backend.projections.ProjectionRebuilder;
import com.example.backend.projections.SessionProjection;
import com.example.backend.repositories.HistoryEntryRepository;
import com.example.backend.repositories.SessionProjectionRepository;
import com.example.backend.repositories.SessionRepository;
import com.example.backend.repositories.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final CreateSessionHandler createSessionHandler;
    private final AddPlayerHandler addPlayerHandler;
    private final IdGenerator idGenerator;
    private final CurrentUserProvider currentUserProvider;
    private final AddBuyInHandler addBuyInHandler;
    private final AddCashOutHandler addCashOutHandler;
    private final ReopenSessionHandler reopenSessionHandler;

    private final SessionProjectionRepository sessionProjectionRepository;
    private final SessionRepository sessionRepository;

    private final CloseSessionHandler closeSessionHandler;
    private final ArchiveSessionHandler archiveSessionHandler;
    private final RemovePlayerHandler removePlayerHandler;
    private final RecordHandHandler recordHandHandler;
    private final HistoryEntryRepository historyEntryRepository;

    private final ProjectionRebuilder projectionRebuilder;
    private final UserRepository userRepository;
    private final JoinSessionHandler joinSessionHandler;
    private final LeaveSessionHandler leaveSessionHandler;

    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {

        String sessionId = idGenerator.nextId();
        String hostId = currentUserProvider.getCurrentUserId();
        String inviteCode;
        do {
            inviteCode = InviteCodeGenerator.generate();
        } while (sessionProjectionRepository.existsByInviteCode(inviteCode));

        CreateSessionCommand command = new CreateSessionCommand(
                sessionId,
                hostId,
                request.name(),
                request.description(),
                inviteCode
        );

        createSessionHandler.handle(command);

        return ResponseEntity
                .created(URI.create("/api/sessions/" + sessionId))
                .body(new CreateSessionResponse(sessionId, inviteCode));
    }

    @PostMapping("/join")
    public ResponseEntity<JoinSessionResponse> joinByInviteCode(@Valid @RequestBody JoinSessionRequest request) {
        String inviteCode = request.inviteCode().trim().toUpperCase(java.util.Locale.ROOT);
        SessionProjection projection = sessionProjectionRepository.findByInviteCodeIgnoreCase(inviteCode)
                .orElseThrow(() -> new SessionNotFoundException("No session was found for that invite code."));
        if (!"ACTIVE".equals(projection.getStatus())) {
            throw new com.example.backend.exception.InvalidSessionStateException("This session is not accepting players.");
        }
        String userId = currentUserProvider.getCurrentUserId();
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedActionException("Your account could not be found."));
        joinSessionHandler.handle(new JoinSessionCommand(projection.getSessionId(), userId, user.getUsername()));
        return ResponseEntity.ok(new JoinSessionResponse(projection.getSessionId()));
    }

    @PostMapping("{sessionId}/players")
    public ResponseEntity<AddPlayerResponse> addPlayerToSession(@PathVariable String sessionId, @Valid @RequestBody AddPlayerRequest request) {

        String hostId = currentUserProvider.getCurrentUserId();

        if (request.linkedUserId() != null && userRepository.findById(request.linkedUserId()).isEmpty()) {
            throw new ValidationException("Selected player account was not found.");
        }

        AddPlayerCommand command = new AddPlayerCommand(
                sessionId,
                hostId,
                request.linkedUserId(),
                request.displayName()
        );

        String playerId = addPlayerHandler.handle(command);
        return ResponseEntity.created(
                URI.create("/api/sessions/" + sessionId + "/players/" + playerId)
        ).body(new AddPlayerResponse(playerId));
    }

    @GetMapping("{sessionId}/player-candidates")
    public ResponseEntity<List<PlayerCandidateResponse>> findPlayerCandidates(
            @PathVariable String sessionId, @RequestParam String query) {
        SessionProjection projection = sessionProjectionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        String currentUserId = currentUserProvider.getCurrentUserId();
        if (!currentUserId.equals(projection.getHostId())) {
            throw new UnauthorizedActionException("Only the host can add players.");
        }
        if (!"ACTIVE".equals(projection.getStatus())) {
            throw new com.example.backend.exception.InvalidSessionStateException("Players can only be added to an active session.");
        }
        List<PlayerCandidateResponse> candidates;
        if (query.isBlank()) {
            candidates = List.of();
        } else {
            String search = query.trim();
            LinkedHashMap<String, PlayerCandidateResponse> matches = new LinkedHashMap<>();
            userRepository.findByUsernameIgnoreCase(search).ifPresent(user ->
                    matches.put(user.getId(), new PlayerCandidateResponse(user.getId(), user.getUsername())));
            userRepository.findTop10ByUsernameContainingIgnoreCaseOrderByUsernameAsc(search).forEach(user ->
                    matches.putIfAbsent(user.getId(), new PlayerCandidateResponse(user.getId(), user.getUsername())));
            candidates = matches.values().stream().limit(10).toList();
        }
        return ResponseEntity.ok(candidates);
    }

    @PostMapping("{sessionId}/buyins")
    public ResponseEntity<?> addBuyIns(@PathVariable String sessionId, @Valid @RequestBody AddBuyInRequest request) {

        String hostId = currentUserProvider.getCurrentUserId();

        AddBuyInCommand command = new AddBuyInCommand(
                sessionId,
                hostId,
                request.deltas()
        );
        String buyInId = addBuyInHandler.handle(command);

        return ResponseEntity
                .created(URI.create("/api/sessions/" + sessionId + "/buyins/" + buyInId))
                .body(new AddBuyInResponse(buyInId));
    }

    @PostMapping("{sessionId}/cashouts")
    public ResponseEntity<AddCashOutResponse> addCashOut(@PathVariable String sessionId, @Valid @RequestBody AddCashOutRequest request) {
        String hostId = currentUserProvider.getCurrentUserId();

        AddCashOutCommand command = new AddCashOutCommand(
                sessionId,
                hostId,
                request.playerId(),
                request.amount()
        );

        String cashOutId = addCashOutHandler.handle(command);

        return ResponseEntity
                .created(URI.create("/api/sessions/" + sessionId + "/cashouts/" + cashOutId))
                .body(new AddCashOutResponse(cashOutId));
    }

    private SessionResponse toResponse(SessionProjection projection) {

        List<PlayerProjection> playerProjection = projection.getPlayers();

        List<PlayerResponse> playerResponses = playerProjection.stream().map(p -> new PlayerResponse(
                p.getPlayerId(),
                p.getUserId(),
                p.getDisplayName(),
                p.getTotalBuyIn(),
                p.getTotalCashOut(),
                p.getTotalCashOut().add(p.getChipStack().subtract(p.getTotalBuyIn())),
                p.getChipStack(),
                p.getStatus()
        )).toList();


        BigDecimal totalBuyIns = playerResponses.stream()
                .map(PlayerResponse::totalBuyIn)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCashOuts = playerResponses.stream()
                .map(PlayerResponse::totalCashOut)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SessionResponse(
                projection.getSessionId(),
                projection.getSessionName(),
                projection.getHostId(),
                currentUserProvider.getCurrentUserId().equals(projection.getHostId()) ? projection.getInviteCode() : null,
                projection.getStatus(),
                playerResponses,
                totalBuyIns,
                totalCashOuts
        );

    }

    @PostMapping("{sessionId}/reopen")
    public ResponseEntity<ReopenSessionResponse> reopenSession(@PathVariable String sessionId) {
        String hostId = currentUserProvider.getCurrentUserId();
        ReopenSessionCommand command = new ReopenSessionCommand(sessionId, hostId);
        reopenSessionHandler.handle(command);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("{sessionId}/close")
    public ResponseEntity<CloseSessionResponse> closeSession(@PathVariable String sessionId) {

        String hostId = currentUserProvider.getCurrentUserId();

        CloseSessionCommand command = new CloseSessionCommand(sessionId, hostId);
        closeSessionHandler.handle(command);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("{sessionId}/archive")
    public ResponseEntity<ArchiveSessionResponse> archiveSession(@PathVariable String sessionId) {

        String hostId = currentUserProvider.getCurrentUserId();

        ArchiveSessionCommand command = new ArchiveSessionCommand(sessionId, hostId);

        archiveSessionHandler.handle(command);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<PagedResponse<SessionSummaryResponse>> getSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(defaultValue = "newest") String sort) {

        String currentUserId = currentUserProvider.getCurrentUserId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 50));
        Sort sorting = switch (sort) {
            case "oldest" -> Sort.by(Sort.Order.asc("createdAt"));
            case "name-asc" -> Sort.by(Sort.Order.asc("sessionName"));
            case "name-desc" -> Sort.by(Sort.Order.desc("sessionName"));
            case "active" -> Sort.by(Sort.Order.asc("status"), Sort.Order.desc("createdAt"));
            case "newest" -> Sort.by(Sort.Order.desc("createdAt"));
            default -> throw new ValidationException("Unsupported session sort order.");
        };
        Pageable pageable = PageRequest.of(safePage, safeSize, sorting.and(Sort.by(Sort.Order.asc("sessionId"))));
        Page<SessionProjection> sessionPage = switch (filter) {
            case "all" -> sessionProjectionRepository.findByHostIdOrPlayersUserId(currentUserId, currentUserId, pageable);
            case "hosted" -> sessionProjectionRepository.findByHostId(currentUserId, pageable);
            case "player" -> sessionProjectionRepository.findByPlayersUserIdAndHostIdNot(currentUserId, currentUserId, pageable);
            default -> throw new ValidationException("Unsupported session filter.");
        };
        List<SessionProjection> sessionProjections = sessionPage.getContent();

        Map<String, String> hostNames = new java.util.HashMap<>();
        userRepository.findAllById(sessionProjections.stream()
                        .map(SessionProjection::getHostId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(HashSet::new)))
                .forEach(host -> hostNames.put(host.getId(), host.getUsername()));

        List<SessionSummaryResponse> responses = sessionProjections.stream().map(s -> new SessionSummaryResponse(
                    s.getSessionId(),
                    s.getSessionName(),
                    s.getHostId(),
                    hostNames.get(s.getHostId()),
                    s.getPlayers() == null ? 0 : (int) s.getPlayers().stream()
                            .filter(player -> "ACTIVE".equals(player.getStatus()))
                            .count(),
                    s.getPlayers() == null ? null : s.getPlayers().stream()
                            .filter(player -> currentUserId.equals(player.getUserId())
                                    && "INACTIVE".equals(player.getStatus()))
                            .map(player -> "NOT_IN_SESSION")
                            .findFirst()
                            .orElse(null),
                    s.getStatus(),
                    s.getCreatedAt()
            )
        ).toList();

        return ResponseEntity.ok(new PagedResponse<>(responses, sessionPage.getNumber(), sessionPage.getSize(),
                sessionPage.getTotalElements(), sessionPage.getTotalPages()));
    }

    @DeleteMapping("{sessionId}/players/{playerId}")
    public ResponseEntity<Void> removePlayer(@PathVariable String sessionId, @PathVariable String playerId) {

        String hostId = currentUserProvider.getCurrentUserId();

        RemovePlayerCommand cmd = new RemovePlayerCommand(sessionId, hostId, playerId);
        removePlayerHandler.handle(cmd);

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("{sessionId}/players/me")
    public ResponseEntity<Void> leaveSession(@PathVariable String sessionId) {
        leaveSessionHandler.handle(new LeaveSessionCommand(sessionId, currentUserProvider.getCurrentUserId()));
        return ResponseEntity.noContent().build();
    }


    private SessionResponse toHistoricalResponse(SessionAggregate aggregate) {

        List<PlayerResponse> playerResponses = aggregate.getPlayers().values().stream()
                .map(p -> new PlayerResponse(
                        p.playerId(),
                        p.userId(),
                        p.displayName(),
                        p.totalBuyIn(),
                        p.totalCashOut(),
                        p.totalCashOut().add(p.chipStack().subtract(p.totalBuyIn())),
                        p.chipStack(),
                        p.status().name()
                ))
                .toList();

        BigDecimal totalBuyIns = playerResponses.stream()
                .map(PlayerResponse::totalBuyIn)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCashOuts = playerResponses.stream()
                .map(PlayerResponse::totalCashOut)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SessionResponse(
                aggregate.getId(),
                aggregate.getName(),
                aggregate.getHostId(),
                currentUserProvider.getCurrentUserId().equals(aggregate.getHostId()) ? aggregate.getInviteCode() : null,
                aggregate.getStatus().name(),
                playerResponses,
                totalBuyIns,
                totalCashOuts
        );
    }

    @GetMapping("{sessionId}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable String sessionId, @RequestParam(required = false) Instant asOf) {
        if (asOf != null) {
            SessionAggregate aggregate = sessionRepository.loadAsOf(sessionId, asOf);
            SessionProjection projection = sessionProjectionRepository.findById(sessionId)
                    .orElseThrow(() -> new SessionNotFoundException(sessionId));
            requireSessionAccess(projection);
            return ResponseEntity.ok(toHistoricalResponse(aggregate));
        }

        SessionProjection projection = sessionProjectionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        requireSessionAccess(projection);
        return ResponseEntity.ok(toResponse(projection));
    }

    @PostMapping("{sessionId}/hands")
    public ResponseEntity<RecordHandResponse> recordHand(@PathVariable String sessionId, @Valid @RequestBody RecordHandRequest request) {
        String hostId = currentUserProvider.getCurrentUserId();

        RecordHandCommand command = new RecordHandCommand(
                sessionId,
                hostId,
                request.deltas()
        );

        String handId = recordHandHandler.handle(command);

        return ResponseEntity
                .created(URI.create("/api/sessions/" + sessionId + "/hands/" + handId))
                .body(new RecordHandResponse(handId));
    }

    @GetMapping("{sessionId}/history")
    public ResponseEntity<List<HistoryEntryResponse>> getHistory(@PathVariable String sessionId) {
        SessionProjection projection = sessionProjectionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        requireSessionAccess(projection);
        List<HistoryEntry> entries = historyEntryRepository.findBySessionIdOrderByVersionAsc(sessionId);

        List<HistoryEntryResponse> response = entries.stream()
                .map(e -> new HistoryEntryResponse(e.getEventType(), e.getDescription(), e.getTimestamp()))
                .toList();

        return ResponseEntity.ok(response);
    }

    private void requireSessionAccess(SessionProjection projection) {
        String userId = currentUserProvider.getCurrentUserId();
        boolean isHost = userId.equals(projection.getHostId());
        boolean isParticipant = projection.getPlayers() != null && projection.getPlayers().stream()
                .anyMatch(player -> userId.equals(player.getUserId()));
        if (!isHost && !isParticipant) {
            throw new UnauthorizedActionException("You do not have access to this session.");
        }
    }

    @PostMapping("{sessionId}/rebuild-projections")
    public ResponseEntity<Void> rebuildProjections(@PathVariable String sessionId) {
        String currentUserId = currentUserProvider.getCurrentUserId();
        SessionProjection projection = sessionProjectionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!projection.getHostId().equals(currentUserId)) {
            throw new UnauthorizedActionException("Only the host can rebuild this session's projections.");
        }
        SessionAggregate aggregate = sessionRepository.load(sessionId);
        if (aggregate.getId() == null) {
            throw new SessionNotFoundException(sessionId);
        }
        projectionRebuilder.rebuildForSession(sessionId);
        return ResponseEntity.noContent().build();
    }
    
    
} 
