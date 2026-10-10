package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record JoinSessionRequest(@NotBlank String inviteCode) {}
