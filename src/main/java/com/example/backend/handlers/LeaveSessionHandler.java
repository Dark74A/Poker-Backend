package com.example.backend.handlers;

import com.example.backend.commands.LeaveSessionCommand;
import com.example.backend.executor.AggregateCommandExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LeaveSessionHandler {
    private final AggregateCommandExecutor executor;

    public void handle(LeaveSessionCommand command) {
        executor.execute(command.sessionId(), aggregate -> {
            aggregate.handle(command);
            return null;
        });
    }
}
