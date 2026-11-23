package vn.bully.worker;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import vn.bully.worker.support.*;
import vn.bully.worker.rpc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

class WorkerStateTest {
    AtomicLong clock;
    WorkerState state;
    long generation;
    @BeforeEach void setup() {
        clock = new AtomicLong(1000);
        state = new WorkerState(5000, 60000, clock::get, new EventLog("Test"));
        generation = state.control(ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(3).build()).getGeneration();
    }
    ControlRequest.Builder control(String action) { return ControlRequest.newBuilder().setAction(action).setCoordinatorId(3).setGeneration(generation); }
    WorkerReply reserve(String client, String request) {
        return state.control(control("RESERVE").setClientId(client).setRequestId(request).build());
    }
    PdfRequest job(WorkerReply s) {
        return PdfRequest.newBuilder().setClientId(s.getClientId()).setSessionId(s.getSessionId()).setAccessToken(s.getAccessToken()).build();
    }
    @Test void concurrentReservationsHaveExactlyOneWinner() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(20); var futures = new ArrayList<Future<WorkerReply>>();
            for (int i = 0; i < 20; i++) { String id = "c" + i; futures.add(pool.submit(() -> { barrier.await(); return reserve(id, "r" + id); })); }
            int winners = 0; for (var future : futures) if (future.get().getAccepted()) winners++;
            assertEquals(1, winners); assertEquals("RESERVED", state.status().getWorkerStatus());
        }
    }
    @Test void duplicateRequestRecoversSameToken() {
        var first = reserve("a", "r"); var retry = reserve("a", "r");
        assertEquals(first.getSessionId(), retry.getSessionId()); assertEquals(first.getAccessToken(), retry.getAccessToken());
        assertFalse(reserve("a", "other").getAccepted());
        assertTrue(state.status().getAccessToken().isEmpty());
    }
    @Test void staleCoordinatorCannotMutateSessionAfterFailover() {
        var s = reserve("a", "r"); clock.addAndGet(5001);
        var claim = state.control(ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(2).build());
        assertTrue(claim.getAccepted()); assertTrue(claim.getGeneration() > generation);
        assertFalse(reserve("b", "r2").getAccepted());
        assertFalse(state.control(control("RELEASE").setClientId("a").setSessionId(s.getSessionId()).setAccessToken(s.getAccessToken()).build()).getAccepted());
        state.begin(job(s), "Merge"); state.finish(); assertEquals(s.getSessionId(), state.status().getSessionId());
    }
    @Test void anotherCoordinatorCannotClaimValidLease() {
        assertFalse(state.control(ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(2).build()).getAccepted());
    }
    @Test void sameIdCannotClaimValidLease() {
        // A second process started with the same ID (duplicate not yet detected) must not steal the live lease.
        var duplicate = state.control(ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(3).build());
        assertFalse(duplicate.getAccepted());
        assertEquals(generation, duplicate.getGeneration());
        assertTrue(state.control(control("RENEW").build()).getAccepted());
    }
    @Test void jobLongerThanTimeoutKeepsSessionAndResetsIdleTimer() {
        var s = reserve("a", "r"); state.begin(job(s), "Merge"); clock.addAndGet(90000);
        assertEquals("PROCESSING", state.status().getWorkerStatus()); state.finish();
        assertEquals("RESERVED", state.status().getWorkerStatus()); assertEquals(60000, state.status().getSessionRemainingMs());
    }
    @Test void idleSessionExpiresAndCannotExecute() {
        var s = reserve("a", "r"); clock.addAndGet(60000);
        assertEquals("AVAILABLE", state.status().getWorkerStatus()); assertEquals("EXPIRED", state.status().getSessionStatus());
        assertThrows(io.grpc.StatusRuntimeException.class, () -> state.begin(job(s), "Split"));
    }
    @Test void releaseDuringJobWaitsUntilCompletion() {
        var s = reserve("a", "r"); state.begin(job(s), "Merge");
        assertTrue(state.control(control("RELEASE").setClientId("a").setSessionId(s.getSessionId()).setAccessToken(s.getAccessToken()).build()).getAccepted());
        assertEquals("PROCESSING", state.status().getWorkerStatus()); assertFalse(reserve("b", "r2").getAccepted());
        state.finish(); assertEquals("AVAILABLE", state.status().getWorkerStatus()); assertTrue(reserve("b", "r2").getAccepted());
    }
    @Test void invalidTokenWrongClientAndConcurrentJobAreRejected() {
        var s = reserve("a", "r");
        assertThrows(io.grpc.StatusRuntimeException.class, () -> state.begin(job(s).toBuilder().setAccessToken("wrong").build(), "Merge"));
        assertThrows(io.grpc.StatusRuntimeException.class, () -> state.begin(job(s).toBuilder().setClientId("b").build(), "Merge"));
        state.begin(job(s), "Merge"); assertThrows(io.grpc.StatusRuntimeException.class, () -> state.begin(job(s), "Merge")); state.finish();
    }
    @Test void wrongOwnerCannotRelease() {
        var s = reserve("a", "r");
        assertFalse(state.control(control("RELEASE").setClientId("b").setSessionId(s.getSessionId()).setAccessToken(s.getAccessToken()).build()).getAccepted());
        assertEquals(s.getSessionId(), state.status().getSessionId());
    }
    @Test void droppingLeasePreservesClientSession() {
        var s = reserve("a", "r"); state.control(control("DROP").build());
        assertTrue(state.control(ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(2).build()).getAccepted());
        state.begin(job(s), "Merge"); state.finish(); assertEquals(s.getSessionId(), state.status().getSessionId());
    }
}
