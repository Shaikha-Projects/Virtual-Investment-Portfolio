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
        emitters.put(email, emitter);

        //remove emitters when user disconnects or the connection times out
        emitter.onCompletion(() -> emitters.remove(email));
        emitter.onTimeout(() -> emitters.remove(email));
        emitter.onError(error -> emitters.remove(email));

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
            emitter.send(SseEmitter.event().name("investment-notification").data(message));
        } catch (IOException e) {
            emitters.remove(email);
        }

    }
}
