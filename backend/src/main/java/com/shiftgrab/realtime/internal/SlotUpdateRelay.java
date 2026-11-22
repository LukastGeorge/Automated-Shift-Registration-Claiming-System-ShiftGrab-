package com.shiftgrab.realtime.internal;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fan-out between backend instances (SG-40). A browser's WebSocket is connected to one instance, but the claim may
 * be served by another. Every update is therefore published on a Redis channel; each instance subscribes and
 * forwards what it receives to its own WebSocket clients ({@code /topic/shifts}).
 *
 * <p>Without Redis (in-memory lock profile, single instance) or while Redis is unreachable, updates are delivered
 * to the local clients only: live updates degrade, nothing else is affected.
 */
@Component
class SlotUpdateRelay {

    static final String CHANNEL = "shiftgrab:slot-updates";
    static final String DESTINATION = "/topic/shifts";
    private static final Logger log = LoggerFactory.getLogger(SlotUpdateRelay.class);

    private final SimpMessagingTemplate stomp;
    private final JsonMapper json;
    private final ObjectProvider<RedissonClient> redisson;
    private volatile RTopic topic;

    SlotUpdateRelay(SimpMessagingTemplate stomp, JsonMapper json, ObjectProvider<RedissonClient> redisson) {
        this.stomp = stomp;
        this.json = json;
        this.redisson = redisson;
    }

    @EventListener(ApplicationReadyEvent.class)
    void subscribeOnStartup() {
        subscribed();
    }

    void publish(SlotUpdate update) {
        String payload = json.writeValueAsString(update);
        RTopic channel = subscribed();
        if (channel == null) {
            deliverLocally(payload);
            return;
        }
        try {
            channel.publish(payload); // this instance receives it too, through its own subscription
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, live update delivered to this instance's clients only: {}", e.getMessage());
            deliverLocally(payload);
        }
    }

    /** The Redis channel, subscribing on first use; null when there is no Redis or it cannot be reached. */
    private RTopic subscribed() {
        RTopic current = topic;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (topic == null) {
                RedissonClient client = redisson.getIfAvailable();
                if (client == null) {
                    return null;
                }
                try {
                    RTopic channel = client.getTopic(CHANNEL, StringCodec.INSTANCE);
                    channel.addListener(String.class, (name, payload) -> deliverLocally(payload));
                    topic = channel;
                } catch (RuntimeException e) {
                    log.warn("Cannot subscribe to {} yet: {}", CHANNEL, e.getMessage());
                    return null;
                }
            }
            return topic;
        }
    }

    private void deliverLocally(String payload) {
        stomp.convertAndSend(DESTINATION, payload);
    }
}
