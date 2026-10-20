package com.ga.investmentportfolio.Service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationService {
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    //subscription method
    public SseEmitter subscribe(String email) {
        //create an SseEmitter, 30 minute timeout
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        //store emitter using email
        SseEmitter previous = emitters.put(email, emitter);
        if (previous != null) {
            try {
                previous.send(SseEmitter.event().name("subscription-replaced").data("Another tab is connected"));
            } catch (IOException | IllegalStateException ignored) {
                // The previous client may already have disconnected.
            }
            previous.complete();
        }

        //remove emitters when user disconnects or the connection times out
        emitter.onCompletion(() -> emitters.remove(email, emitter));
        emitter.onTimeout(() -> emitters.remove(email, emitter));
        emitter.onError(error -> emitters.remove(email, emitter));

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(email, emitter);
            emitter.complete();
        }
        return emitter;
    }

    //send notification method
    public void sendNotification(String email, String message) {

        //retrieve user's open connection
        SseEmitter emitter = emitters.get(email);

        //if user not currently connected
        if (emitter == null) {
            return;
        }

        //send the SSE event
        try {
            emitter.send(SseEmitter.event().id(java.util.UUID.randomUUID().toString()).name("investment-notification").data(message));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(email, emitter);
        }

    }
}
