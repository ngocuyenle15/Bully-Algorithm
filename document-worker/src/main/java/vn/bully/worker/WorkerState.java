package vn.bully.worker;

import io.grpc.Status;
import vn.bully.worker.support.*;
import vn.bully.worker.rpc.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;

/** All ownership decisions are serialized here, independently of TCP coordinators. */
public final class WorkerState {
    private final LongSupplier clock;
    private final long leaseMs, timeoutMs;
    private final EventLog log;
    private int coordinator;
    private long generation, leaseUntil, lastActivity;
    private String session = "", client = "", request = "", token = "", operation = "";
    private boolean processing, releasePending;
    private SessionStatus sessionStatus = SessionStatus.RELEASED;

    public WorkerState(long leaseMs, long timeoutMs, EventLog log) {
        this(leaseMs, timeoutMs, () -> System.nanoTime() / 1_000_000, log);
    }
    public WorkerState(long leaseMs, long timeoutMs, LongSupplier clock, EventLog log) {
        this.leaseMs = leaseMs; this.timeoutMs = timeoutMs; this.clock = clock; this.log = log;
    }
    private void expire() {
        if (!session.isEmpty() && !processing && clock.getAsLong() - lastActivity >= timeoutMs)
            clear(SessionStatus.EXPIRED);
    }
    private void clear(SessionStatus status) {
        log.info("Session " + status + " client=" + client + " session=" + session);
        session = ""; client = ""; request = ""; token = ""; releasePending = false; sessionStatus = status;
    }
    public synchronized WorkerReply status() { expire(); return reply(true, "", false); }
    private WorkerReply reply(boolean accepted, String error, boolean includeToken) {
        long now = clock.getAsLong();
        return WorkerReply.newBuilder().setAccepted(accepted).setError(error)
            .setCoordinatorId(coordinator).setGeneration(generation)
            .setLeaseRemainingMs(Math.max(0, leaseUntil - now))
            .setWorkerStatus(processing ? "PROCESSING" : session.isEmpty() ? "AVAILABLE" : "RESERVED")
            .setSessionId(session).setClientId(client).setRequestId(request)
            .setAccessToken(includeToken ? token : "")
            .setSessionRemainingMs(session.isEmpty() ? 0 : processing ? timeoutMs : Math.max(0, timeoutMs - (now - lastActivity)))
            .setOperation(operation).setSessionStatus(sessionStatus.name()).build();
    }
    public synchronized WorkerReply control(ControlRequest r) {
        expire(); long now = clock.getAsLong();
        if (r.getAction().equals("CLAIM")) {
            if (r.getCoordinatorId() <= 0) return reply(false, "Invalid coordinator", false);
            if (leaseUntil > now) return reply(false, "Coordinator lease is occupied", false);
            coordinator = r.getCoordinatorId(); generation++; leaseUntil = now + leaseMs;
            log.info("Coordinator accepted id=" + coordinator + " generation=" + generation);
            return reply(true, "", false);
        }
        if (r.getCoordinatorId() != coordinator || r.getGeneration() != generation || leaseUntil <= now)
            return reply(false, "Stale coordinator or expired lease", false);
        switch (r.getAction()) {
            case "RENEW" -> leaseUntil = now + leaseMs;
            case "DROP" -> { leaseUntil = now; log.info("Coordinator lease dropped id=" + coordinator); }
            case "RESERVE" -> {
                if (r.getClientId().isBlank() || r.getRequestId().isBlank()) return reply(false, "Missing client/request", false);
                if (!session.isEmpty()) {
                    if (client.equals(r.getClientId()) && request.equals(r.getRequestId())) return reply(true, "", true);
                    return reply(false, "Worker busy", false);
                }
                session = UUID.randomUUID().toString(); client = r.getClientId(); request = r.getRequestId();
                byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
                sessionStatus = SessionStatus.ACTIVE; lastActivity = now;
                log.info("Session created client=" + client + " request=" + request + " session=" + session);
                return reply(true, "", true);
            }
            case "RELEASE" -> {
                if (session.isEmpty()) return reply(true, "", false);
                if (!matches(r.getClientId(), r.getSessionId(), r.getAccessToken())) return reply(false, "Invalid session owner", false);
                if (processing) releasePending = true;
                else clear(SessionStatus.RELEASED);
            }
            case "STATUS" -> { }
            default -> { return reply(false, "Unknown control action", false); }
        }
        return reply(true, "", false);
    }
    private boolean matches(String clientId, String sessionId, String accessToken) {
        return !session.isEmpty() && client.equals(clientId) && session.equals(sessionId) && token.equals(accessToken);
    }
    public synchronized void begin(PdfRequest r, String name) {
        expire();
        if (!matches(r.getClientId(), r.getSessionId(), r.getAccessToken()) || releasePending)
            throw Status.PERMISSION_DENIED.withDescription("Invalid or expired session").asRuntimeException();
        if (processing) throw Status.RESOURCE_EXHAUSTED.withDescription("Worker is processing another job").asRuntimeException();
        processing = true; operation = name; lastActivity = clock.getAsLong();
        log.info("Processing " + name + " client=" + client);
    }
    public synchronized void finish() {
        processing = false; operation = ""; lastActivity = clock.getAsLong();
        if (releasePending) clear(SessionStatus.RELEASED);
        log.info("Job finished; worker=" + (session.isEmpty() ? "AVAILABLE" : "RESERVED"));
    }
}
