package com.example.backend.handlers;

import com.example.backend.commands.JoinSessionCommand;
import com.example.backend.executor.AggregateCommandExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JoinSessionHandler {
    private final AggregateCommandExecutor executor;

    public String handle(JoinSessionCommand command) {
        return executor.execute(command.sessionId(), aggregate -> aggregate.handle(command));
    }
}
