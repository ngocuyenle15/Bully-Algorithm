package vn.bully.client.support;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class EventLog {
    private final Logger logger;
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    public EventLog(String name) { logger = Logger.getLogger(name); }
    public synchronized void listen(Consumer<String> listener) {
        listeners.add(listener); history.forEach(listener);
    }
    public synchronized void info(String text) {
        logger.info(text);
        String line = LocalTime.now().withNano(0) + " " + text;
        history.addLast(line); if (history.size() > 300) history.removeFirst();
        listeners.forEach(listener -> listener.accept(line));
    }
}
