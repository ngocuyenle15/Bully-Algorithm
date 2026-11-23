package vn.bully.server;

import vn.bully.server.support.*;
import vn.bully.server.rpc.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerNode implements AutoCloseable {
    private record Member(NodeAddress address, String bootId, long lastSeenNanos, boolean seed) { }

    private record PendingGrant(TcpMessage request, long reservedNanos) { }

    private final int id, heartbeatTimeout, failureLimit, electionTimeout;
    private final long queueTimeoutNanos, workerHealthNanos;
    /** Last time each queued requestId was polled by its client; silent clients are dropped. */
    private final Map<String, Long> lastPoll = new HashMap<>();
    /** Sessions reserved by pump() whose client has not yet received ACCESS_GRANTED. */
    private final Map<String, PendingGrant> pendingGrants = new HashMap<>();
    private volatile long workerOkNanos;
    private volatile boolean workerOkEver, workerWasReachable = true;
    private final ConcurrentMap<Integer, Member> members = new ConcurrentHashMap<>();
    private final WorkerClient worker;
    private final InetAddress bindAddress;
    private final ServerDiscovery discovery;
    private final long discoveryTimeoutNanos, electionReadyNanos;
    private final ConcurrentMap<Integer, Long> peerConflicts = new ConcurrentHashMap<>();
    public final EventLog log;
    private final ServerSocket listener;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    /** Lease renewal / heartbeat. Must never be blocked by slow peers, otherwise the lease expires. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** Periodic GET_COORDINATOR probing of peers runs separately from lease renewal. */
    private final ScheduledExecutorService prober = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean election = new AtomicBoolean();
    private final LinkedHashMap<String, TcpMessage> queue = new LinkedHashMap<>();
    private final ConcurrentMap<Integer, Boolean> alive = new ConcurrentHashMap<>();
    private volatile boolean running = true, coordinator;
    private volatile int coordinatorId;
    private volatile long generation;
    private volatile long selfConflictUntilNanos;
    private volatile int failures;
    private volatile String display = "Starting";
    private volatile long announcedGeneration = -1;
    /** Local, conservative copy of the Worker lease expiry (monotonic clock). */
    private volatile long leaseDeadlineNanos;

    public ServerNode(Config config) throws IOException {
        id = config.number("id", 1);
        var seeds = config.nodes();
        var configuredSelf = seeds.stream().filter(n -> n.id() == id).findFirst();
        heartbeatTimeout = config.number("heartbeat-timeout-ms", 1000);
        failureLimit = config.number("heartbeat-failures", 3);
        electionTimeout = config.number("election-ms", 2000);
        int heartbeatInterval = config.number("heartbeat-ms", 1000);
        queueTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.number("queue-timeout-ms", 3000));
        // A node may only claim/answer elections if it reached the Worker recently (gRPC deadline is 3 s).
        workerHealthNanos = TimeUnit.MILLISECONDS.toNanos((long) heartbeatInterval * failureLimit + 3000);
        bindAddress = config.bindAddress();
        int defaultPort = configuredSelf.map(NodeAddress::port).orElseGet(() -> {
            if (id > 56535) throw new IllegalArgumentException("port is required for server ID greater than 56535");
            return 9000 + id;
        });
        int port = config.port("port", defaultPort, false);
        if (configuredSelf.isPresent() && port != configuredSelf.get().port())
            throw new IllegalArgumentException("port must match this server in the servers list: " + configuredSelf.get());
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(bindAddress, port));
        log = new EventLog("Server-" + id);
        for (var seed : seeds) members.put(seed.id(), new Member(seed, "seed", 0, true));
        String advertisedHost = configuredSelf.map(NodeAddress::host).orElseGet(() -> {
            String configured = config.get("advertise-address", "").trim();
            if (!configured.isEmpty()) return configured;
            if (!bindAddress.isAnyLocalAddress()) return bindAddress.getHostAddress();
            return InetAddress.getLoopbackAddress().getHostAddress();
        });
        members.put(id, new Member(new NodeAddress(id, advertisedHost, port), "self", 0, true));
        worker = new WorkerClient(config.worker(), bindAddress, 0);
        boolean discoveryEnabled = config.flag("discovery-enabled", true);
        int discoveryTimeout = config.number("discovery-timeout-ms", 5000);
        int discoveryInterval = config.number("discovery-interval-ms", 1000);
        if (discoveryTimeout <= discoveryInterval)
            throw new IllegalArgumentException("discovery-timeout-ms must be greater than discovery-interval-ms");
        discoveryTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(discoveryTimeout);
        int warmup = discoveryEnabled ? config.number("discovery-warmup-ms", 2500) : 0;
        electionReadyNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(warmup);
        discovery = discoveryEnabled
            ? new ServerDiscovery(config.clusterId(), id, port, config.discoveryGroup(),
                config.port("discovery-port", 8999, false), discoveryInterval, bindAddress,
                this::onDiscovered, log)
            : null;
        connections.submit(this::accept);
        scheduler.scheduleWithFixedDelay(this::tick, 0, heartbeatInterval, TimeUnit.MILLISECONDS);
        prober.scheduleWithFixedDelay(this::probePeers, 2L * heartbeatInterval, 2L * heartbeatInterval, TimeUnit.MILLISECONDS);
        log.info("Server started id=" + id + " listening=" + listener.getLocalSocketAddress()
            + (discoveryEnabled ? " discovery-warmup-ms=" + warmup : " discovery=disabled"));
    }
    public int id() { return id; }
    public InetSocketAddress listenAddress() { return (InetSocketAddress) listener.getLocalSocketAddress(); }
    public String display() { return display; }
    private void onDiscovered(ServerDiscovery.Hello hello) {
        long now = System.nanoTime();
        if (hello.nodeId() == id) {
            selfConflictUntilNanos = now + discoveryTimeoutNanos;
            log.info("DUPLICATE SERVER ID detected id=" + id + " remote="
                + hello.sourceAddress().getHostAddress() + ":" + hello.tcpPort());
            return;
        }
        var address = new NodeAddress(hello.nodeId(), hello.sourceAddress().getHostAddress(), hello.tcpPort());
        members.compute(hello.nodeId(), (key, old) -> {
            if (old != null && !old.bootId().equals(hello.bootId()) && !old.bootId().equals("seed")
                && now - old.lastSeenNanos() <= discoveryTimeoutNanos) {
                peerConflicts.put(key, now + discoveryTimeoutNanos);
                log.info("Duplicate discovered server ID=" + key + "; excluding it from election");
            }
            boolean changed = old == null || !old.address().equals(address) || !old.bootId().equals(hello.bootId());
            if (changed) log.info("Discovered server " + address);
            return new Member(address, hello.bootId(), now, old != null && old.seed());
        });
        alive.put(hello.nodeId(), true);
    }
    private void expireDiscovery() {
        long now = System.nanoTime();
        for (var entry : members.entrySet()) {
            var member = entry.getValue();
            if (entry.getKey() != id && !member.seed() && now - member.lastSeenNanos() > discoveryTimeoutNanos) {
                if (members.remove(entry.getKey(), member)) {
                    alive.put(entry.getKey(), false);
                    log.info("Discovery timeout server=" + entry.getKey());
                }
            }
        }
        peerConflicts.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
    private List<NodeAddress> activeNodes() {
        long now = System.nanoTime();
        return members.entrySet().stream()
            .filter(entry -> entry.getKey() == id || !peerConflicts.containsKey(entry.getKey()))
            .filter(entry -> entry.getValue().seed() || now - entry.getValue().lastSeenNanos() <= discoveryTimeoutNanos)
            .map(entry -> entry.getValue().address())
            .sorted(Comparator.comparingInt(NodeAddress::id))
            .toList();
    }
    private Optional<NodeAddress> node(int nodeId) {
        return activeNodes().stream().filter(node -> node.id() == nodeId).findFirst();
    }
    private boolean warmingUp() { return System.nanoTime() < electionReadyNanos; }
    private boolean idConflict() { return System.nanoTime() < selfConflictUntilNanos; }
    private void markWorkerReachable() { workerOkNanos = System.nanoTime(); workerOkEver = true; }
    private boolean workerHealthy() { return workerOkEver && System.nanoTime() - workerOkNanos <= workerHealthNanos; }
    /**
     * Bully: replying ELECTION_OK is a promise "I am alive and I will take over".
     * A node may only make that promise when it can actually run (and win) its own election.
     */
    private boolean eligible() { return running && !warmingUp() && !idConflict() && workerHealthy(); }
    /** Sends one message to many peers concurrently; total time is bounded by a single call timeout. */
    private Map<Integer, TcpMessage> broadcast(List<NodeAddress> targets, MessageType type, Map<String, Object> payload) {
        var calls = new LinkedHashMap<Integer, Future<TcpMessage>>();
        for (var node : targets) calls.put(node.id(), connections.submit(() -> Wire.call(node,
            Wire.message(type, UUID.randomUUID().toString(), Integer.toString(id), payload), heartbeatTimeout, bindAddress, 0)));
        var replies = new LinkedHashMap<Integer, TcpMessage>();
        for (var call : calls.entrySet()) {
            try { replies.put(call.getKey(), call.getValue().get()); alive.put(call.getKey(), true); }
            catch (ExecutionException e) { alive.put(call.getKey(), false); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return replies;
    }
    /** Probes the Worker so the node knows whether it is eligible to become Coordinator. */
    private void probeWorker() {
        try { worker.status(); markWorkerReachable(); }
        catch (Exception e) { /* health derives from the last successful contact */ }
        boolean reachable = workerHealthy();
        if (reachable != workerWasReachable) {
            workerWasReachable = reachable;
            log.info(reachable ? "Worker reachable; eligible for election"
                : "Worker unreachable; not answering ELECTION_OK and not claiming coordinator");
        }
    }
    /** Worker is the source of truth: a node is Coordinator only while it holds a valid lease. */
    private boolean holdsLease(int candidate) {
        try {
            var status = worker.status(); markWorkerReachable();
            return status.getCoordinatorId() == candidate && status.getLeaseRemainingMs() > 0;
        } catch (Exception e) { return false; }
    }
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
        int current = coordinatorId; long currentGeneration = generation;
        var address = node(current);
        if (current == 0 || address.isEmpty())
            return answer(r, MessageType.ELECTION_IN_PROGRESS, Map.of("eligible", eligible()));
        return answer(r, MessageType.COORDINATOR_INFO, Map.of("id", current,
            "host", address.get().host(), "port", address.get().port(), "generation", currentGeneration,
            "eligible", eligible()));
    }
    private TcpMessage handle(TcpMessage r) {
        if (idConflict()) return answer(r, MessageType.ELECTION_IN_PROGRESS, Map.of("error", "Duplicate server ID"));
        switch (r.type()) {
            case ELECTION -> {
                if (Integer.parseInt(r.senderId()) >= id) return coordinatorInfo(r);
                if (!eligible()) {
                    // Answering OK without being able to run/win our own election (warming up, no Worker)
                    // would make the lower candidate step back while nobody takes over.
                    log.info("ELECTION received from=" + r.senderId() + "; not eligible (warm-up or Worker unreachable), not answering OK");
                    return answer(r, MessageType.ELECTION_IN_PROGRESS, Map.of("error", "Not eligible", "eligible", false));
                }
                if (coordinator) {
                    // Already the winner: re-announce so lower nodes learn the result (Bully COORDINATOR message).
                    log.info("ELECTION received from=" + r.senderId() + "; already coordinator, send ELECTION_OK and re-announce");
                    connections.submit(this::announce);
                } else {
                    log.info("ELECTION received from=" + r.senderId() + "; send ELECTION_OK and start own election");
                    startElection();
                }
                return answer(r, MessageType.ELECTION_OK, Map.of("id", id));
            }
            case COORDINATOR -> {
                try {
                    var status = worker.status(); markWorkerReachable();
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
            if (queue.containsKey(r.requestId())) lastPoll.put(r.requestId(), System.nanoTime());
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
                queue.put(r.requestId(), r); lastPoll.put(r.requestId(), System.nanoTime());
                log.info("Access requested client=" + clientId + " request=" + r.requestId());
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
        pendingGrants.remove(r.requestId()); // the client is alive and is receiving its token
        return answer(r, MessageType.ACCESS_GRANTED, Map.of("sessionId", session.getSessionId(),
            "accessToken", session.getAccessToken(), "remainingMs", session.getSessionRemainingMs()));
    }
    private synchronized void pump() {
        if (!coordinator || queue.isEmpty()) return;
        var first = queue.entrySet().iterator().next();
        var result = reserve(first.getValue());
        if (result.getAccepted()) {
            queue.remove(first.getKey()); lastPoll.remove(first.getKey());
            pendingGrants.putIfAbsent(first.getKey(), new PendingGrant(first.getValue(), System.nanoTime()));
            log.info("Access granted client=" + result.getClientId() + " session=" + result.getSessionId());
        }
    }
    /** Drops queued clients that stopped polling and revokes grants that were never picked up. */
    private synchronized void expireQueue() {
        if (!coordinator) return;
        long now = System.nanoTime();
        var stale = queue.entrySet().iterator();
        while (stale.hasNext()) {
            var entry = stale.next();
            if (now - lastPoll.getOrDefault(entry.getKey(), now) > queueTimeoutNanos) {
                log.info("Queued client disconnected; removed client=" + Wire.string(entry.getValue(), "clientId")
                    + " request=" + entry.getKey());
                stale.remove(); lastPoll.remove(entry.getKey());
            }
        }
        var pending = pendingGrants.entrySet().iterator();
        while (pending.hasNext()) {
            var entry = pending.next();
            if (now - entry.getValue().reservedNanos() <= queueTimeoutNanos) continue;
            pending.remove();
            var request = entry.getValue().request();
            var status = worker.status();
            // Only revoke if that exact reservation is still unused (client never received the token).
            if (!status.getRequestId().equals(entry.getKey())
                || !status.getClientId().equals(Wire.string(request, "clientId"))
                || status.getWorkerStatus().equals("PROCESSING")) continue;
            var reserved = reserve(request);
            if (!reserved.getAccepted()) continue;
            var released = worker.control(control("RELEASE").setClientId(reserved.getClientId())
                .setSessionId(reserved.getSessionId()).setAccessToken(reserved.getAccessToken()).build());
            log.info("Grant not picked up; revoked client=" + reserved.getClientId() + " accepted=" + released.getAccepted());
        }
    }
    private synchronized void demote(boolean drop) {
        if (coordinator && drop) {
            try { worker.control(control("DROP").build()); } catch (Exception ignored) { }
        }
        coordinator = false; coordinatorId = 0; queue.clear(); lastPoll.clear(); pendingGrants.clear();
    }
    private void startElection() {
        if (!running || warmingUp() || idConflict() || !workerHealthy() || !election.compareAndSet(false, true)) return;
        connections.submit(() -> {
            try {
                log.info("Election started id=" + id + "; sending ELECTION to higher IDs");
                boolean higher = false;
                // Classic Bully: ELECTION goes to all higher IDs at once, so a dead host costs one timeout, not one per host.
                var higherNodes = activeNodes().stream().filter(node -> node.id() > id).toList();
                for (var reply : broadcast(higherNodes, MessageType.ELECTION, Map.of()).entrySet()) {
                    if (reply.getValue().type() == MessageType.ELECTION_OK) {
                        higher = true;
                        log.info("ELECTION_OK (Answer) received from=" + reply.getKey());
                    }
                }
                if (higher) {
                    // Only a current coordinator yields its lease. A follower keeps its known coordinatorId:
                    // clearing it would race with (and erase) a COORDINATOR announcement already received.
                    if (coordinator) demote(true);
                    Thread.sleep(electionTimeout); // If no announcement arrives, the next tick starts another election.
                } else {
                    Thread.sleep(electionTimeout);
                    synchronized (this) {
                        if (!running || coordinator) return;
                        long sent = System.nanoTime();
                        var result = worker.control(ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(id).build());
                        markWorkerReachable();
                        if (result.getAccepted()) {
                            leaseDeadlineNanos = sent + TimeUnit.MILLISECONDS.toNanos(result.getLeaseRemainingMs());
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
        long announced = generation;
        announcedGeneration = announced;
        var peers = activeNodes().stream().filter(node -> node.id() != id).toList();
        broadcast(peers, MessageType.COORDINATOR, Map.of("id", id, "generation", announced));
        log.info("COORDINATOR announcement sent id=" + id + " generation=" + announced);
    }
    private void tick() {
        if (!running) return;
        try {
            expireDiscovery();
            if (!coordinator) probeWorker();
            if (idConflict()) {
                if (coordinator) demote(true);
                render("ID_CONFLICT");
                return;
            }
            if (warmingUp()) {
                render("DISCOVERING");
                return;
            }
            if (coordinator) {
                synchronized (this) {
                    long sent = System.nanoTime();
                    var renewal = worker.control(control("RENEW").build());
                    markWorkerReachable();
                    if (!renewal.getAccepted()) { log.info("Coordinator lease lost"); demote(false); }
                    else {
                        // Measured from the send time, so the local deadline is never later than the Worker's.
                        leaseDeadlineNanos = sent + TimeUnit.MILLISECONDS.toNanos(renewal.getLeaseRemainingMs());
                        expireQueue(); pump();
                    }
                }
                if (coordinator && announcedGeneration != generation) {
                    announcedGeneration = generation; // announce asynchronously: never delay lease renewal
                    connections.submit(this::announce);
                }
            } else if (!election.get()) {
                if (coordinatorId == 0) startElection();
                else {
                    try {
                        var address = node(coordinatorId).orElseThrow(() -> new IOException("Coordinator not discovered"));
                        var response = Wire.call(address, Wire.message(MessageType.HEARTBEAT, UUID.randomUUID().toString(), Integer.toString(id), Map.of()), heartbeatTimeout, bindAddress, 0);
                        int next = response.type() == MessageType.COORDINATOR_INFO ? Wire.integer(response, "id") : 0;
                        if (next != 0 && next != coordinatorId && holdsLease(next)) {
                            // Old coordinator stepped down and points to a newer one: follow it if the Worker agrees.
                            coordinatorId = next; failures = 0;
                            log.info("Coordinator changed id=" + next + " (learned from heartbeat)");
                        } else {
                            if (response.type() != MessageType.HEARTBEAT_ACK) throw new IOException("Coordinator is not active");
                            failures = 0; alive.put(address.id(), true);
                        }
                    } catch (IOException e) {
                        failures++; log.info("Heartbeat timeout coordinator=" + coordinatorId + " failures=" + failures);
                        if (failures >= failureLimit) { alive.put(coordinatorId, false); coordinatorId = 0; startElection(); }
                    }
                }
            }
            render(coordinator ? "COORDINATOR" : !workerHealthy() ? "WORKER_UNREACHABLE" : election.get() ? "ELECTING" : "STANDBY");
        } catch (Exception e) {
            log.info("Server tick failed: " + e.getMessage());
            // A single failed RENEW (gRPC deadline, transient network error) must not throw away a lease that the
            // Worker still considers valid: nobody else can claim it before it expires anyway, so stepping down early
            // only leaves the cluster without a Coordinator. Every access operation is still fenced by the Worker.
            if (coordinator && System.nanoTime() >= leaseDeadlineNanos) demote(false);
            else if (coordinator) log.info("Keeping coordinator role until local lease deadline; will retry RENEW");
        }
    }
    /**
     * Membership/consistency probe (every 2 heartbeats, own thread, peers queried concurrently):
     * - a Coordinator that sees an eligible higher ID starts an election so it can yield (Bully "bully" step);
     * - a follower that sees a lower Coordinator starts an election to take over;
     * - a follower without Coordinator adopts a higher one that the Worker confirms holds the lease.
     */
    private void probePeers() {
        if (!running || warmingUp() || idConflict()) return;
        try {
            var peers = activeNodes().stream().filter(node -> node.id() != id).toList();
            for (var reply : broadcast(peers, MessageType.GET_COORDINATOR, Map.of()).entrySet()) {
                int peer = reply.getKey(); var response = reply.getValue();
                boolean peerEligible = !Boolean.FALSE.equals(response.payload().get("eligible"));
                if (coordinator) {
                    if (peer > id && peerEligible) startElection();
                    continue;
                }
                if (response.type() != MessageType.COORDINATOR_INFO) continue;
                int candidate = Wire.integer(response, "id");
                if (candidate < id) { if (!election.get()) startElection(); }
                // Adopt a higher coordinator even while electing, but only if it really holds the lease.
                else if (coordinatorId == 0 && holdsLease(candidate)) {
                    coordinatorId = candidate; failures = 0;
                    log.info("Coordinator changed id=" + candidate + " (learned from GET_COORDINATOR)");
                }
            }
        } catch (Exception e) { if (running) log.info("Peer probe failed: " + e.getMessage()); }
    }
    private synchronized void render(String state) {
        var lines = new StringBuilder("Server " + id + " | " + state
            + " | listen " + listener.getLocalSocketAddress() + "\nCoordinator: " + coordinatorId
            + " | generation: " + generation + "\n\nDiscovered servers:\n");
        for (var node : activeNodes()) lines.append(node).append(" ")
            .append(node.id() == id || alive.getOrDefault(node.id(), false) ? "ALIVE" : "OFFLINE")
            .append(node.id() == coordinatorId ? " COORDINATOR" : " STANDBY").append('\n');
        lines.append("\nRequest queue:\n"); int position = 0;
        for (var r : queue.values()) lines.append(++position).append(". ").append(Wire.string(r, "clientId")).append(" | ")
            .append(Wire.string(r, "username")).append(" | ").append(r.requestId()).append('\n');
        display = lines.toString();
    }
    @Override public void close() {
        running = false; // Simulated crash: do not drop lease; Worker must detect expiry.
        try { listener.close(); } catch (IOException ignored) { }
        if (discovery != null) discovery.close();
        scheduler.shutdownNow(); prober.shutdownNow(); connections.shutdownNow(); worker.close();
        log.info("Server stopped id=" + id);
    }
}
