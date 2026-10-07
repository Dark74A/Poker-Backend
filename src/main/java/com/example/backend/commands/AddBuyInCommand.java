package com.example.backend.commands;

import java.math.BigDecimal;
import java.util.Map;

public record AddBuyInCommand(
        String sessionId,
        String hostId,
        Map<String, BigDecimal> deltas
) {}
