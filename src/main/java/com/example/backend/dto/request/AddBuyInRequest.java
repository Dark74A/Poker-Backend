package com.example.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.Map;

public record AddBuyInRequest(
        @NotEmpty(message = "Atleast one buy-in is required.")
        Map<String, BigDecimal> deltas
) {
}
