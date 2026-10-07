package com.example.backend.config;

import com.example.backend.service.JwtService;
import com.example.backend.repositories.SessionProjectionRepository;
import com.example.backend.projections.SessionProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WebSocketAuthenticationInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final SessionProjectionRepository sessionProjectionRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            List<String> authorization = accessor.getNativeHeader("Authorization");
            
            if (authorization != null && !authorization.isEmpty()) {
                String token = authorization.get(0);
                if (token.startsWith("Bearer ")) {
                    token = token.substring(7);
                }
                
                try {
                    String userId = jwtService.extractUserId(token);
                    if (userId != null) {
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
                        accessor.setUser(auth);
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid JWT token", e);
                }
            } else {
                throw new IllegalArgumentException("Missing Authorization header");
            }
        }

        if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            String userId = accessor.getUser() == null ? null : accessor.getUser().getName();
            String prefix = "/topic/sessions/";
            if (destination == null || !destination.startsWith(prefix) || userId == null) {
                throw new IllegalArgumentException("Unauthorized subscription");
            }
            String sessionId = destination.substring(prefix.length());
            SessionProjection projection = sessionProjectionRepository.findById(sessionId).orElse(null);
            boolean allowed = projection != null && (userId.equals(projection.getHostId()) ||
                    (projection.getPlayers() != null && projection.getPlayers().stream()
                            .anyMatch(player -> userId.equals(player.getUserId()))));
            if (!allowed) throw new IllegalArgumentException("Unauthorized subscription");
        }
        
        return message;
    }
}
