package com.example.backend.projections;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisLiveUpdateListener implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String[] fields = new String(message.getBody(), StandardCharsets.UTF_8).split("\\n", -1);
            if (fields.length != 3 || fields[0].isBlank() || !fields[0].matches("[a-zA-Z0-9-]+")
                    || !fields[1].matches("[A-Za-z]+") || !fields[2].matches("\\d+")) {
                log.warn("Ignoring malformed Redis session update");
                return;
            }
            String json = "{\"type\":\"" + fields[1] + "\",\"version\":" + fields[2] + "}";
            messagingTemplate.convertAndSend("/topic/sessions/" + fields[0], json);
        } catch (Exception e) {
            log.error("Could not relay Redis session update", e);
        }
    }
}
