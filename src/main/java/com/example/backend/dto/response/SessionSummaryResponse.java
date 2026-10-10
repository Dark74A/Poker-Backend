package com.example.backend.dto.response;

import java.time.Instant;

public record SessionSummaryResponse(
        String sessionId,
        String name,
        String hostId,
        String hostName,
        int playerCount,
        String currentUserStatus,
        String status,
        Instant createdAt
) {}
