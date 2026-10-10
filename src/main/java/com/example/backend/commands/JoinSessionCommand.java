package com.example.backend.commands;

public record JoinSessionCommand(String sessionId, String userId, String username) {}
