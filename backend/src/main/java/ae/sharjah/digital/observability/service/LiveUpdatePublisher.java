package ae.sharjah.digital.observability.service;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** In-memory SSE for a single API replica; multi-node fanout requires a shared broker. */
@Service
public class LiveUpdatePublisher {
    private final CopyOnWriteArrayList<SseEmitter> listeners = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(120000L);
        listeners.add(emitter);
        emitter.onCompletion(() -> listeners.remove(emitter));
        emitter.onTimeout(() -> listeners.remove(emitter));
        return emitter;
    }

    public void broadcastSourceChange(String source) {
        for (SseEmitter listener : listeners) {
            try {
                listener.send(SseEmitter.event().name("metrics-updated").data(source));
            } catch (IOException | IllegalStateException error) {
                listeners.remove(listener);
                listener.complete();
            }
        }
    }
}
