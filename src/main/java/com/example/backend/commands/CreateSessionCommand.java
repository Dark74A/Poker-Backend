package com.example.backend.commands;

public record CreateSessionCommand(

        String sessionId,
        String hostId,
        String name,
        String description,
        String inviteCode
) {
    public CreateSessionCommand(String sessionId, String hostId, String name, String description) {
        this(sessionId, hostId, name, description,
                com.example.backend.helpers.InviteCodeGenerator.generate());
    }
}
