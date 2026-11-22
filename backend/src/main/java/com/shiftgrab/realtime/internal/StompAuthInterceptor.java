package com.shiftgrab.realtime.internal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Browsers cannot put a bearer token on the WebSocket handshake, so the token travels in the STOMP CONNECT frame
 * and is validated here exactly like an API call (signature, issuer, audience, expiry). The channel is read-only:
 * clients may only subscribe to {@code /topic/shifts} and never send.
 */
class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter;

    StompAuthInterceptor(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        this.decoder = decoder;
        this.converter = converter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new AccessDeniedException("A bearer token is required to connect");
            }
            try {
                accessor.setUser(converter.convert(decoder.decode(header.substring("Bearer ".length()))));
            } catch (JwtException e) {
                throw new AccessDeniedException("Invalid token", e);
            }
        } else if (command == StompCommand.SUBSCRIBE && !SlotUpdateRelay.DESTINATION.equals(accessor.getDestination())) {
            throw new AccessDeniedException("Only " + SlotUpdateRelay.DESTINATION + " can be subscribed");
        } else if (command == StompCommand.SEND) {
            throw new AccessDeniedException("This channel is read-only");
        }
        return message;
    }
}
