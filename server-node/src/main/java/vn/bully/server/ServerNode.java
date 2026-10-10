package vn.bully.server;

import vn.bully.common.*;
import vn.bully.common.rpc.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerNode implements AutoCloseable {
    private final int id, heartbeatTimeout, failureLimit, electionTimeout;
    private final List<NodeAddress> nodes;
    private final WorkerClient worker;
    public final EventLog log;
    private final ServerSocket listener;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean election = new AtomicBoolean();
    private final LinkedHashMap<String, TcpMessage> queue = new LinkedHashMap<>();
    private final ConcurrentMap<Integer, Boolean> alive = new ConcurrentHashMap<>();
    private volatile boolean running = true, coordinator;
    private volatile int coordinatorId;
    private volatile long generation;
    private int failures, ticks;
    private volatile String display = "Starting";
    private long announcedGeneration = -1;

    public ServerNode(Config config) throws IOException {
        id = config.number("id", 1); nodes = config.nodes();
        var self = nodes.stream().filter(n -> n.id() == id).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Server ID is missing from servers list"));
        heartbeatTimeout = config.number("heartbeat-timeout-ms", 1000);
        failureLimit = config.number("heartbeat-failures", 3);
        electionTimeout = config.number("election-ms", 2000);
        worker = new WorkerClient(config.worker()); log = new EventLog("Server-" + id);
        listener = new ServerSocket(config.number("port", self.port()));
        connections.submit(this::accept);
        scheduler.scheduleWithFixedDelay(this::tick, 0, config.number("heartbeat-ms", 1000), TimeUnit.MILLISECONDS);
        log.info("Server started id=" + id + " port=" + listener.getLocalPort());
    }
    public int id() { return id; }
    public String display() { return display; }
    private void accept() {
        while (running) {
            try {
                Socket socket = listener.accept();
                connections.submit(() -> {
                    try (socket) {
                        socket.setSoTimeout(5000);
                        var request = Wire.read(socket);
                        Wire.write(socket, handle(request));
                    } catch (Exception e) { if (running) log.info("TCP error: " + e.getMessage()); }
                });
            } catch (IOException e) { if (running) log.info("Listener error: " + e.getMessage()); }
        }
    }
    private TcpMessage answer(TcpMessage r, MessageType type, Map<String, Object> data) {
        return Wire.message(type, r.requestId(), Integer.toString(id), data);
    }
    private TcpMessage coordinatorInfo(TcpMessage r) {
        var address = nodes.stream().filter(n -> n.id() == coordinatorId).findFirst();
        if (address.isEmpty()) return answer(r, MessageType.ELECTION_IN_PROGRESS, Map.of());
        return answer(r, MessageType.COORDINATOR_INFO, Map.of("id", coordinatorId,
            "host", address.get().host(), "port", address.get().port(), "generation", generation));
    }
    private TcpMessage handle(TcpMessage r) {
        switch (r.type()) {
            case ELECTION -> {
                if (Integer.parseInt(r.senderId()) >= id) return coordinatorInfo(r);
                log.info("Election received from=" + r.senderId());
                startElection();
                return answer(r, MessageType.ELECTION_OK, Map.of("id", id));
            }
            case COORDINATOR -> {
                try {
                    var status = worker.status();
                    if (status.getCoordinatorId() == Wire.integer(r, "id") && status.getLeaseRemainingMs() > 0) {
                        synchronized (this) {
                            if (coordinator && status.getCoordinatorId() != id) demote(false);
                            coordinatorId = status.getCoordinatorId(); generation = status.getGeneration(); failures = 0;
                        }
                        log.info("Coordinator changed id=" + coordinatorId + " generation=" + generation);
                    }
                } catch (Exception e) { log.info("Cannot verify coordinator: " + e.getMessage()); }
                return coordinatorInfo(r);
            }
            case HEARTBEAT -> {
                if (!coordinator) return coordinatorInfo(r);
                log.info("Heartbeat received from=" + r.senderId());
                return answer(r, MessageType.HEARTBEAT_ACK, Map.of("id", id));
            }
            case GET_COORDINATOR -> { return coordinatorInfo(r); }
            default -> { return access(r); }
        }
    }
    private ControlRequest.Builder control(String action) {
        return ControlRequest.newBuilder().setAction(action).setCoordinatorId(id).setGeneration(generation);
    }
    private synchronized TcpMessage access(TcpMessage r) {
        if (!coordinator) return coordinatorInfo(r);
        if (r.requestId() == null || r.requestId().isBlank() || Wire.string(r, "clientId").isBlank())
            return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", false, "error", "Missing request/client ID"));
        try {
            // Verify fencing on every operation, even cancel/status.
            var status = worker.control(control("STATUS").build());
            if (!status.getAccepted()) { demote(false); return coordinatorInfo(r); }
            if (r.type() == MessageType.ACCESS_CANCEL) {
                // A queued request may have been reserved just before its client cancels.
                // Recover its token through the fenced coordinator and release that reservation.
                if (status.getClientId().equals(Wire.string(r, "clientId")) && status.getRequestId().equals(r.requestId())) {
                    var reserved = reserve(r);
                    var released = worker.control(control("RELEASE").setClientId(reserved.getClientId())
                        .setSessionId(reserved.getSessionId()).setAccessToken(reserved.getAccessToken()).build());
                    if (!released.getAccepted()) return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", false, "error", released.getError()));
                }
                var queued = queue.get(r.requestId());
                if (queued != null && Wire.string(queued, "clientId").equals(Wire.string(r, "clientId"))) queue.remove(r.requestId());
                pump();
                return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", true, "cancelled", true));
            }
            if (r.type() == MessageType.ACCESS_RELEASE) {
                var released = worker.control(control("RELEASE").setClientId(Wire.string(r, "clientId"))
                    .setSessionId(Wire.string(r, "sessionId")).setAccessToken(Wire.string(r, "accessToken")).build());
                log.info("Access release client=" + Wire.string(r, "clientId") + " accepted=" + released.getAccepted());
                pump();
                return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", released.getAccepted(),
                    "error", released.getError(), "workerStatus", released.getWorkerStatus()));
            }
            if (r.type() != MessageType.ACCESS_REQUEST && r.type() != MessageType.ACCESS_STATUS)
                return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", false, "error", "Unsupported message"));
            String clientId = Wire.string(r, "clientId");
            if (status.getClientId().equals(clientId)) {
                if (!status.getRequestId().equals(r.requestId()))
                    return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", false, "error", "Client already owns another request"));
                return grant(r, reserve(r));
            }
            for (var pending : queue.values()) {
                if (Wire.string(pending, "clientId").equals(clientId) && !pending.requestId().equals(r.requestId()))
                    return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", false, "error", "Client already queued"));
                if (pending.requestId().equals(r.requestId()) && !Wire.string(pending, "clientId").equals(clientId))
                    return answer(r, MessageType.ACCESS_STATUS, Map.of("accepted", false, "error", "Request ID belongs to another client"));
            }
            if (!queue.containsKey(r.requestId())) {
                queue.put(r.requestId(), r); log.info("Access requested client=" + clientId + " request=" + r.requestId());
            }
            pump();
            status = worker.status();
            if (status.getClientId().equals(clientId) && status.getRequestId().equals(r.requestId())) return grant(r, reserve(r));
            int position = new ArrayList<>(queue.keySet()).indexOf(r.requestId()) + 1;
            return answer(r, MessageType.ACCESS_QUEUED, Map.of("position", position));
        } catch (Exception e) {
            log.info("Worker unavailable: " + e.getMessage());
            return answer(r, MessageType.ELECTION_IN_PROGRESS, Map.of("error", "Worker unavailable; retry"));
        }
    }
    private WorkerReply reserve(TcpMessage r) {
        return worker.control(control("RESERVE").setClientId(Wire.string(r, "clientId"))
            .setRequestId(r.requestId()).setUsername(Wire.string(r, "username")).build());
    }
    private TcpMessage grant(TcpMessage r, WorkerReply session) {
        if (!session.getAccepted()) return answer(r, MessageType.ELECTION_IN_PROGRESS, Map.of("error", session.getError()));
        return answer(r, MessageType.ACCESS_GRANTED, Map.of("sessionId", session.getSessionId(),
            "accessToken", session.getAccessToken(), "remainingMs", session.getSessionRemainingMs()));
    }
    private synchronized void pump() {
        if (!coordinator || queue.isEmpty()) return;
        var first = queue.entrySet().iterator().next();
        var result = reserve(first.getValue());
        if (result.getAccepted()) {
            queue.remove(first.getKey()); log.info("Access granted client=" + result.getClientId() + " session=" + result.getSessionId());
        }
    }
    private synchronized void demote(boolean drop) {
        if (coordinator && drop) {
            try { worker.control(control("DROP").build()); } catch (Exception ignored) { }
        }
        coordinator = false; coordinatorId = 0; queue.clear();
    }
    private void startElection() {
        if (!running || !election.compareAndSet(false, true)) return;
        connections.submit(() -> {
            try {
                log.info("Election started id=" + id);
                boolean higher = false;
                for (var node : nodes) if (node.id() > id) {
                    try {
                        var response = Wire.call(node, Wire.message(MessageType.ELECTION, UUID.randomUUID().toString(), Integer.toString(id), Map.of()), heartbeatTimeout);
                        alive.put(node.id(), true);
                        if (response.type() == MessageType.ELECTION_OK) { higher = true; log.info("Election OK from=" + node.id()); }
                    } catch (IOException e) { alive.put(node.id(), false); }
                }
                if (higher) {
                    demote(true);
                    Thread.sleep(electionTimeout); // If no announcement arrives, the next tick starts another election.
                } else {
                    Thread.sleep(electionTimeout);
                    synchronized (this) {
                        if (!running || coordinator) return;
                        var result = worker.control(ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(id).build());
                        if (result.getAccepted()) {
                            coordinator = true; coordinatorId = id; generation = result.getGeneration(); failures = 0;
                            log.info("Coordinator elected id=" + id + " generation=" + generation);
                        } else log.info("Waiting for coordinator lease expiry");
                    }
                    if (coordinator) announce();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { log.info("Election failed: " + e.getMessage()); }
            finally { election.set(false); }
        });
    }
    private void announce() {
        for (var node : nodes) if (node.id() != id) {
            try { Wire.call(node, Wire.message(MessageType.COORDINATOR, UUID.randomUUID().toString(), Integer.toString(id),
                Map.of("id", id, "generation", generation)), heartbeatTimeout); }
            catch (IOException ignored) { }
        }
        announcedGeneration = generation;
    }
    private void tick() {
        if (!running) return;
        try {
            if (coordinator) {
                synchronized (this) {
                    var renewal = worker.control(control("RENEW").build());
                    if (!renewal.getAccepted()) { log.info("Coordinator lease lost"); demote(false); }
                    else pump();
                }
                if (coordinator && announcedGeneration != generation) announce();
            } else if (!election.get()) {
                if (coordinatorId == 0) startElection();
                else {
                    var address = nodes.stream().filter(n -> n.id() == coordinatorId).findFirst().orElseThrow();
                    try {
                        var response = Wire.call(address, Wire.message(MessageType.HEARTBEAT, UUID.randomUUID().toString(), Integer.toString(id), Map.of()), heartbeatTimeout);
                        if (response.type() != MessageType.HEARTBEAT_ACK) throw new IOException("Coordinator is not active");
                        failures = 0; alive.put(address.id(), true);
                    } catch (IOException e) {
                        failures++; log.info("Heartbeat timeout coordinator=" + coordinatorId + " failures=" + failures);
                        if (failures >= failureLimit) { alive.put(address.id(), false); coordinatorId = 0; startElection(); }
                    }
                }
            }
            if (++ticks % 2 == 0) {
                for (var node : nodes) if (node.id() != id) {
                    try {
                        var response = Wire.call(node, Wire.message(MessageType.GET_COORDINATOR, UUID.randomUUID().toString(), Integer.toString(id), Map.of()), heartbeatTimeout);
                        alive.put(node.id(), true);
                        if (coordinator && node.id() > id) startElection();
                        if (!coordinator && !election.get() && response.type() == MessageType.COORDINATOR_INFO) {
                            int candidate = Wire.integer(response, "id");
                            if (candidate < id) startElection();
                            else if (coordinatorId == 0) coordinatorId = candidate;
                        }
                    } catch (IOException e) { alive.put(node.id(), false); }
                }
            }
            synchronized (this) {
                var lines = new StringBuilder("Server " + id + " | " + (coordinator ? "COORDINATOR" : election.get() ? "ELECTING" : "STANDBY")
                    + " | port " + listener.getLocalPort() + "\nCoordinator: " + coordinatorId + " | generation: " + generation + "\n\nServers:\n");
                for (var node : nodes) lines.append(node).append(" ").append(node.id() == id || alive.getOrDefault(node.id(), false) ? "ALIVE" : "OFFLINE")
                    .append(node.id() == coordinatorId ? " COORDINATOR" : " STANDBY").append('\n');
                lines.append("\nRequest queue:\n"); int position = 0;
                for (var r : queue.values()) lines.append(++position).append(". ").append(Wire.string(r, "clientId")).append(" | ")
                    .append(Wire.string(r, "username")).append(" | ").append(r.requestId()).append('\n');
                display = lines.toString();
            }
        } catch (Exception e) {
            log.info("Server tick failed: " + e.getMessage());
            if (coordinator) demote(false);
        }
    }
    @Override public void close() {
        running = false; // Simulated crash: do not drop lease; Worker must detect expiry.
        try { listener.close(); } catch (IOException ignored) { }
        scheduler.shutdownNow(); connections.shutdownNow(); worker.close();
        log.info("Server stopped id=" + id);
    }
}
