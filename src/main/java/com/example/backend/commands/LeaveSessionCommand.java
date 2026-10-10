package com.example.backend.commands;

public record LeaveSessionCommand(String sessionId, String userId) {}
