package vn.bully.client;

import vn.bully.client.support.*;
import vn.bully.client.rpc.*;
import com.google.protobuf.ByteString;
import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ClientNode implements AutoCloseable {
    public record Snapshot(String clientId, String username, String connection, String coordinator,
                           String requestId, String sessionId, String state, int position, long remainingMs, boolean processing) {}
    private final String id, username;
    private final List<NodeAddress> servers;
    private final WorkerClient worker;
    private final InetAddress bindAddress;
    private final int localPort;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    /** Concurrent lookups; with a fixed local-port only one socket can use it at a time, so stay sequential. */
    private final ExecutorService lookups;
    public final EventLog log;
    private volatile NodeAddress coordinator;
    private String request = "", session = "", token = "", command = "", state = "IDLE", connection = "CONNECTING";
    private long remaining;
    private int position;
    private boolean processing;
    private volatile boolean running = true;

    public ClientNode(Config config) {
        id = config.get("id", UUID.randomUUID().toString()); username = config.get("username", "Client " + id);
        bindAddress = config.bindAddress(); localPort = config.port("local-port", 0, true);
        lookups = localPort == 0 ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newSingleThreadExecutor();
        servers = config.nodes(); worker = new WorkerClient(config.worker(), bindAddress, config.port("grpc-local-port", 0, true));
        log = new EventLog("Client-" + id);
        log.info("Client network interface=" + bindAddress.getHostAddress() + " TCP local-port=" + localPort);
        timer.scheduleWithFixedDelay(this::tick, 0, 700, TimeUnit.MILLISECONDS);
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(id, username, connection, coordinator == null ? "Unknown" : coordinator.toString(),
            request, session, state, position, remaining, processing);
    }
    public synchronized void requestAccess() {
        if (!request.isEmpty() || !session.isEmpty()) return;
        request = UUID.randomUUID().toString(); state = "REQUESTING";
        log.info("Request sent request=" + request);
    }
    public synchronized void cancelRequest() {
        if (!request.isEmpty() && session.isEmpty()) { command = "CANCEL"; state = "CANCELLING"; }
    }
    public synchronized void releaseAccess() {
        if (!session.isEmpty() && !processing) { command = "RELEASE"; state = "RELEASING"; }
    }
    /**
     * Asks every configured server concurrently and trusts the answer with the highest Worker generation.
     * Generation is issued by the Worker and only grows, so a server that still remembers a dead
     * Coordinator (stale view during failover) cannot redirect the client to it.
     */
    private NodeAddress discover() {
        var calls = new ArrayList<Future<TcpMessage>>();
        for (var server : servers) calls.add(lookups.submit(() -> Wire.call(server,
            Wire.message(MessageType.GET_COORDINATOR, UUID.randomUUID().toString(), id, Map.of()), 700, bindAddress, localPort)));
        NodeAddress best = null; long bestGeneration = -1;
        for (var call : calls) {
            try {
                var response = call.get();
                if (response.type() != MessageType.COORDINATOR_INFO) continue;
                long generation = Long.parseLong(Wire.string(response, "generation").isEmpty() ? "0" : Wire.string(response, "generation"));
                if (generation > bestGeneration) {
                    bestGeneration = generation;
                    best = new NodeAddress(Wire.integer(response, "id"), Wire.string(response, "host"), Wire.integer(response, "port"));
                }
            } catch (ExecutionException ignored) {
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return best;
    }
    private void tick() {
        if (!running) return;
        try {
            // Query Worker independently of Coordinator so an existing session survives failover.
            var status = worker.status();
            synchronized (this) {
                if (!session.isEmpty()) {
                    if (!status.getSessionId().equals(session)) {
                        log.info("Session expired or released session=" + session); clear(); state = "EXPIRED";
                    } else remaining = status.getSessionRemainingMs();
                }
            }
        } catch (Exception e) { synchronized (this) { connection = "WORKER_UNAVAILABLE"; } }
        if (coordinator == null) {
            var found = discover();
            if (found != null) { coordinator = found; log.info("Coordinator changed: " + found); }
        }
        var address = coordinator;
        if (address == null) { synchronized (this) { connection = "WAITING_FOR_COORDINATOR"; } return; }
        String currentRequest, currentCommand;
        Map<String, Object> payload;
        synchronized (this) {
            currentRequest = request; currentCommand = command;
            payload = Map.of("clientId", id, "username", username, "sessionId", session, "accessToken", token);
        }
        MessageType type = currentCommand.equals("CANCEL") ? MessageType.ACCESS_CANCEL : currentCommand.equals("RELEASE")
            ? MessageType.ACCESS_RELEASE : currentRequest.isEmpty() ? MessageType.GET_COORDINATOR : MessageType.ACCESS_REQUEST;
        try {
            var response = Wire.call(address, Wire.message(type, currentRequest.isEmpty() ? UUID.randomUUID().toString() : currentRequest, id, payload), 4000, bindAddress, localPort);
            synchronized (this) {
                connection = "CONNECTED";
                if (response.type() == MessageType.ELECTION_IN_PROGRESS) {
                    coordinator = null; connection = "WAITING_FOR_COORDINATOR"; return;
                }
                if (response.type() == MessageType.COORDINATOR_INFO) {
                    coordinator = new NodeAddress(Wire.integer(response, "id"), Wire.string(response, "host"), Wire.integer(response, "port"));
                    return;
                }
                if (!request.equals(currentRequest)) return;
                if (response.type() == MessageType.ACCESS_GRANTED) {
                    if (session.isEmpty()) log.info("Access granted session=" + Wire.string(response, "sessionId"));
                    session = Wire.string(response, "sessionId"); token = Wire.string(response, "accessToken");
                    remaining = ((Number) response.payload().get("remainingMs")).longValue(); position = 0;
                    if (command.isEmpty()) state = processing ? "PROCESSING" : "ACTIVE";
                } else if (response.type() == MessageType.ACCESS_QUEUED) {
                    int next = Wire.integer(response, "position");
                    if (position != next) log.info("Queued position=" + next);
                    position = next; if (command.isEmpty()) state = "QUEUED";
                } else if (response.type() == MessageType.ACCESS_STATUS) {
                    if (Boolean.TRUE.equals(response.payload().get("accepted")) && !currentCommand.isEmpty()) {
                        log.info(currentCommand.equals("CANCEL") ? "Request cancelled" : "Access released"); clear();
                    } else if (Boolean.FALSE.equals(response.payload().get("accepted"))) {
                        log.info("Access error: " + Wire.string(response, "error"));
                        if (currentCommand.isEmpty()) { clear(); state = "ERROR"; }
                    }
                }
            }
        } catch (IOException e) {
            coordinator = null;
            synchronized (this) { connection = "WAITING_FOR_COORDINATOR"; }
            log.info("Coordinator disconnected; retry with existing requestId");
        } catch (Exception e) { log.info("Client error: " + e.getMessage()); }
    }
    private void clear() {
        request = ""; session = ""; token = ""; command = ""; remaining = 0; position = 0; state = "IDLE";
    }
    public byte[] process(String operation, List<Path> files, int start, int end, String watermark) throws IOException {
        PdfRequest.Builder request;
        synchronized (this) {
            if (session.isEmpty() || processing || !command.isEmpty()) throw new IllegalStateException("No available session");
            processing = true; state = "PROCESSING";
            request = PdfRequest.newBuilder().setClientId(id).setSessionId(session).setAccessToken(token)
                .setStartPage(start).setEndPage(end).setWatermark(watermark == null ? "" : watermark);
        }
        try {
            long total = 0;
            for (var file : files) {
                long size = Files.size(file); total += size;
                if (size == 0 || size > 10 * 1024 * 1024 || total > 40 * 1024 * 1024)
                    throw new IllegalArgumentException("Limit: 10 MB/file, 40 MB total");
                request.addFiles(ByteString.copyFrom(Files.readAllBytes(file)));
            }
            log.info("Processing " + operation);
            var stub = worker.processingStub();
            var reply = switch (operation) {
                case "Merge" -> stub.mergePdf(request.build());
                case "Split" -> stub.splitPdf(request.build());
                case "Watermark" -> stub.addWatermark(request.build());
                default -> throw new IllegalArgumentException("Unknown operation");
            };
            log.info("Completed " + operation); return reply.getContent().toByteArray();
        } finally { synchronized (this) { processing = false; state = session.isEmpty() ? "IDLE" : command.isEmpty() ? "ACTIVE" : "RELEASING"; } }
    }
    @Override public void close() { running = false; timer.shutdownNow(); lookups.shutdownNow(); worker.close(); }
}
