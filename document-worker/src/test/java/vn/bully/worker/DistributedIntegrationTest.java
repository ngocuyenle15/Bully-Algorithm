package vn.bully.worker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.pdfbox.Loader;
import io.grpc.Status;
import com.google.protobuf.ByteString;
import vn.bully.worker.support.*;
import vn.bully.worker.testsupport.*;
import vn.bully.worker.rpc.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Starts real, separate JVMs. Build all three executable jars before enabling. */
@EnabledIfSystemProperty(named = "bully.integration", matches = "true")
class DistributedIntegrationTest {
    private final List<Process> processes = new ArrayList<>();
    private final Map<Integer, Process> serverProcesses = new HashMap<>();
    private final Map<Integer, Path> serverLogs = new HashMap<>();
    private final List<NodeAddress> nodes = new ArrayList<>();
    private WorkerClient worker;
    private int workerPort, discoveryPort;
    private String servers;
    private String clusterId;
    private Path logs;

    private static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static int freeUdpPort() throws Exception { try (var socket = new DatagramSocket(0)) { return socket.getLocalPort(); } }
    private Process launch(String project, String name, String... args) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path jar = root.resolve(project + "/target/" + project + "-1.0.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jar), "Build jar first: " + jar);
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-jar", jar.toString(), "--headless", "--bind-address", "127.0.0.1"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logs.resolve(name + ".log").toFile()).start();
        processes.add(process); return process;
    }
    @BeforeEach void startCluster() throws Exception {
        logs = Files.createTempDirectory(Path.of("target"), "integration-");
        workerPort = freePort();
        discoveryPort = freeUdpPort(); clusterId = "integration-" + UUID.randomUUID();
        for (int id = 1; id <= 3; id++) nodes.add(new NodeAddress(id, "127.0.0.1", freePort()));
        servers = String.join(",", nodes.stream().map(NodeAddress::toString).toList());
        launch("document-worker", "worker", "--port", "" + workerPort, "--lease-ms", "1500", "--session-ms", "10000");
        worker = new WorkerClient(new NodeAddress(0, "127.0.0.1", workerPort));
        await(() -> worker.status().getWorkerStatus().equals("AVAILABLE"), 15000);
        startServer(2); startServer(1); startServer(3);
        awaitCoordinator(3);
        for (int id = 1; id <= 3; id++) {
            int currentId = id;
            await(() -> {
                try { return Files.readAllLines(serverLogs.get(currentId)).stream()
                    .filter(line -> line.contains("Discovered server ")).count() >= 2; }
                catch (Exception e) { return false; }
            }, 5000);
        }
    }
    private void startServer(int id) throws Exception { startServer(id, "127.0.0.1:" + workerPort); }
    private void startServer(int id, String workerAddress, String... overrides) throws Exception {
        String name = "server-" + id + "-" + processes.size();
        serverLogs.put(id, logs.resolve(name + ".log"));
        var args = new ArrayList<>(List.of(
            "--id", "" + id, "--port", "" + nodes.get(id - 1).port(), "--worker", workerAddress,
            "--cluster-id", clusterId, "--discovery-group", "239.255.42.99", "--discovery-port", "" + discoveryPort,
            "--discovery-interval-ms", "100", "--discovery-timeout-ms", "700", "--discovery-warmup-ms", "400",
            "--heartbeat-ms", "200", "--heartbeat-timeout-ms", "200", "--heartbeat-failures", "3", "--election-ms", "300"));
        args.addAll(List.of(overrides)); // later options win in Config
        serverProcesses.put(id, launch("server-node", name, args.toArray(String[]::new)));
    }
    private void killServer(int id) throws Exception { var process = serverProcesses.get(id); process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
    private void awaitCoordinator(int id) throws Exception {
        await(() -> {
            var status = worker.status();
            return status.getCoordinatorId() == id && status.getLeaseRemainingMs() > 0
                && call(id, MessageType.GET_COORDINATOR, "probe", "probe", Map.of()).type() == MessageType.COORDINATOR_INFO;
        }, 20000);
    }
    private static void await(BooleanSupplier condition, long timeout) throws Exception {
        long end = System.nanoTime() + timeout * 1_000_000; Throwable last = null;
        while (System.nanoTime() < end) {
            try { if (condition.getAsBoolean()) return; } catch (Throwable e) { last = e; }
            Thread.sleep(100);
        }
        fail("Condition timed out after " + timeout + " ms", last);
    }
    private TcpMessage call(int coordinator, MessageType type, String client, String request, Map<String, Object> extra) {
        var payload = new HashMap<String, Object>(extra); payload.put("clientId", client); payload.put("username", client);
        try { return Wire.call(nodes.get(coordinator - 1), Wire.message(type, request, client, payload), 3000); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private TcpMessage request(int coordinator, String client) { return call(coordinator, MessageType.ACCESS_REQUEST, client, "request-" + client, Map.of()); }
    private void release(int coordinator, String client, TcpMessage grant) {
        var response = call(coordinator, MessageType.ACCESS_RELEASE, client, "request-" + client,
            Map.of("sessionId", Wire.string(grant, "sessionId"), "accessToken", Wire.string(grant, "accessToken")));
        assertEquals(true, response.payload().get("accepted"));
    }
    private PdfRequest.Builder job(String client, TcpMessage grant) throws Exception {
        return PdfRequest.newBuilder().setClientId(client).setSessionId(Wire.string(grant, "sessionId"))
            .setAccessToken(Wire.string(grant, "accessToken")).addFiles(ByteString.copyFrom(PdfServiceTest.pdf(2)));
    }
    @AfterEach void stopCluster() throws Exception {
        for (var process : processes) if (process.isAlive()) process.destroyForcibly();
        for (var process : processes) process.waitFor(5, TimeUnit.SECONDS);
        if (worker != null) worker.close();
    }

    @Test void fifoPdfFailoverRestartRetryAndTimeout() throws Exception {
        var a = request(3, "a"); assertEquals(MessageType.ACCESS_GRANTED, a.type());
        var duplicate = request(3, "a"); assertEquals(Wire.string(a, "sessionId"), Wire.string(duplicate, "sessionId"));
        assertEquals(1, Wire.integer(request(3, "b"), "position"));
        assertEquals(2, Wire.integer(request(3, "c"), "position"));
        var invalid = assertThrows(io.grpc.StatusRuntimeException.class,
            () -> worker.processingStub().splitPdf(job("a", a).setStartPage(1).setEndPage(1).setAccessToken("").build()));
        assertEquals(Status.Code.PERMISSION_DENIED, invalid.getStatus().getCode());
        var merged = worker.processingStub().mergePdf(job("a", a).addFiles(ByteString.copyFrom(PdfServiceTest.pdf(1))).build());
        try (var pdf = Loader.loadPDF(merged.getContent().toByteArray())) { assertEquals(3, pdf.getNumberOfPages()); }
        assertEquals(Wire.string(a, "sessionId"), worker.status().getSessionId());
        long oldGeneration = worker.status().getGeneration();
        killServer(3); awaitCoordinator(2);
        assertEquals(Wire.string(a, "sessionId"), worker.status().getSessionId());
        assertFalse(worker.control(ControlRequest.newBuilder().setAction("RESERVE").setCoordinatorId(3).setGeneration(oldGeneration).setClientId("stale").setRequestId("stale").build()).getAccepted());
        // Existing session continues over gRPC with the new coordinator.
        var split = worker.processingStub().splitPdf(job("a", a).setStartPage(1).setEndPage(1).build());
        try (var pdf = Loader.loadPDF(split.getContent().toByteArray())) { assertEquals(1, pdf.getNumberOfPages()); }
        assertEquals(MessageType.ACCESS_QUEUED, request(2, "b").type());
        assertEquals(MessageType.ACCESS_QUEUED, request(2, "c").type());
        release(2, "a", a); var b = request(2, "b"); assertEquals(MessageType.ACCESS_GRANTED, b.type());
        startServer(3); awaitCoordinator(3);
        assertEquals(Wire.string(b, "sessionId"), worker.status().getSessionId());
        request(3, "c");
        assertEquals(true, call(3, MessageType.ACCESS_CANCEL, "c", "request-c", Map.of()).payload().get("accepted"));
        var watermarked = worker.processingStub().addWatermark(job("b", b).setWatermark("FAILOVER DEMO").build());
        assertFalse(watermarked.getContent().isEmpty());
        release(3, "b", b); assertEquals("AVAILABLE", worker.status().getWorkerStatus());
        // Simulate a lost ACCESS_GRANTED: Worker reserved a session, but client did not receive it.
        var lost = worker.control(ControlRequest.newBuilder().setAction("RESERVE").setCoordinatorId(3)
            .setGeneration(worker.status().getGeneration()).setClientId("lost").setRequestId("request-lost").build());
        assertTrue(lost.getAccepted()); killServer(3); awaitCoordinator(2);
        var recovered = request(2, "lost"); assertEquals(MessageType.ACCESS_GRANTED, recovered.type());
        assertEquals(lost.getSessionId(), Wire.string(recovered, "sessionId"));
        assertEquals(lost.getAccessToken(), Wire.string(recovered, "accessToken"));
        release(2, "lost", recovered);
        var idle = request(2, "idle"); assertEquals(MessageType.ACCESS_GRANTED, idle.type());
        await(() -> worker.status().getWorkerStatus().equals("AVAILABLE"), 15000);
    }

    @Test void simultaneousRequestsGrantExactlyOneSession() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(3); var results = new ArrayList<Future<TcpMessage>>();
            for (int i = 0; i < 3; i++) { String id = "parallel-" + i; results.add(pool.submit(() -> { barrier.await(); return request(3, id); })); }
            int granted = 0, queued = 0;
            for (var result : results) {
                var response = result.get();
                if (response.type() == MessageType.ACCESS_GRANTED) granted++;
                if (response.type() == MessageType.ACCESS_QUEUED) queued++;
            }
            assertEquals(1, granted); assertEquals(2, queued);
        }
    }

    @Test void coordinatorDiesWithThreeRequestsAndLostResponses() throws Exception {
        var sockets = new ArrayList<java.net.Socket>();
        try {
            for (int i = 1; i <= 3; i++) {
                var socket = new java.net.Socket(nodes.get(2).host(), nodes.get(2).port()); sockets.add(socket);
                String client = "inflight-" + i;
                Wire.write(socket, Wire.message(MessageType.ACCESS_REQUEST, "request-" + client, client,
                    Map.of("clientId", client, "username", client)));
            }
            // No client reads its response. A reservation may already exist when the process dies.
            killServer(3);
        } finally { for (var socket : sockets) socket.close(); }
        awaitCoordinator(2);
        int granted = 0, queued = 0;
        for (int i = 1; i <= 3; i++) {
            var response = request(2, "inflight-" + i);
            if (response.type() == MessageType.ACCESS_GRANTED) granted++;
            if (response.type() == MessageType.ACCESS_QUEUED) queued++;
        }
        assertEquals(1, granted); assertEquals(2, queued);
        assertFalse(worker.status().getSessionId().isEmpty());
    }

    @Test void threeRealClientsReconnectAndRecoverAfterOwnerDies() throws Exception {
        var clients = new HashMap<String, Process>();
        for (int i = 1; i <= 3; i++) {
            String id = "real-" + i;
            clients.put(id, launch("client-node", id, "--id", id, "--servers", servers,
                "--worker", "127.0.0.1:" + workerPort, "--request", "true"));
        }
        await(() -> !worker.status().getSessionId().isEmpty(), 15000);
        var before = worker.status();
        killServer(3); awaitCoordinator(2);
        assertEquals(before.getSessionId(), worker.status().getSessionId());
        await(() -> {
            try { return clients.keySet().stream().filter(id -> !id.equals(before.getClientId())).allMatch(id -> {
                try { return Files.readString(logs.resolve(id + ".log")).contains("Coordinator changed: 2@"); }
                catch (Exception e) { return false; }
            }); } catch (Exception e) { return false; }
        }, 15000);
        clients.get(before.getClientId()).destroyForcibly();
        await(() -> !worker.status().getClientId().isEmpty() && !worker.status().getClientId().equals(before.getClientId()), 20000);
        assertTrue(clients.containsKey(worker.status().getClientId()));
    }

    private int viewOf(int server) {
        var response = call(server, MessageType.GET_COORDINATOR, "probe", UUID.randomUUID().toString(), Map.of());
        return response.type() == MessageType.COORDINATOR_INFO ? Wire.integer(response, "id") : 0;
    }
    /** Every listed server must report the expected coordinator continuously for the given time. */
    private void assertStableView(int expected, long millis, int... servers) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < end) {
            for (int server : servers) assertEquals(expected, viewOf(server), "server " + server + " view");
            assertEquals(expected, worker.status().getCoordinatorId());
            Thread.sleep(150);
        }
    }

    @Test void lowerNodeRestartDoesNotCauseEndlessElection() throws Exception {
        // Regression: a restarted low ID used to make followers drop C3 and re-elect forever.
        killServer(1); startServer(1);
        await(() -> viewOf(1) == 3 && viewOf(2) == 3, 8000);
        assertStableView(3, 3000, 1, 2, 3);
    }

    @Test void highestNodeWithoutWorkerDoesNotBlockElection() throws Exception {
        // Regression: server 3 alive on TCP but cut off from the Worker used to answer ELECTION_OK forever.
        killServer(3); startServer(3, "127.0.0.1:1");
        awaitCoordinator(2);
        await(() -> viewOf(1) == 2, 8000);
        assertStableView(2, 3000, 1, 2);
        var granted = request(2, "still-served");
        assertEquals(MessageType.ACCESS_GRANTED, granted.type());
    }

    @Test void disconnectedQueuedClientDoesNotBlockQueue() throws Exception {
        var a = request(3, "holder"); assertEquals(MessageType.ACCESS_GRANTED, a.type());
        assertEquals(MessageType.ACCESS_QUEUED, request(3, "dead").type()); // never polls again
        assertEquals(MessageType.ACCESS_QUEUED, request(3, "waiting").type());
        release(3, "holder", a);
        long start = System.nanoTime();
        await(() -> request(3, "waiting").type() == MessageType.ACCESS_GRANTED, 8000);
        long waited = (System.nanoTime() - start) / 1_000_000;
        assertTrue(waited < 8000, "waiting client must not wait for the dead client's session timeout (10 s)");
        assertEquals("waiting", worker.status().getClientId());
    }

    private long countLines(Path log, String text) throws Exception {
        return Files.readAllLines(log).stream().filter(line -> line.contains(text)).count();
    }

    @Test void warmingUpHigherNodeNeitherAnswersOkNorTriggersElection() throws Exception {
        // Regression: a node still in discovery warm-up answered ELECTION_OK although it could not run its own
        // election yet, and the coordinator re-ran an election against it every probe; both made C2 drop its lease.
        killServer(3); awaitCoordinator(2);
        long electionsBefore = countLines(serverLogs.get(2), "Election started id=2");
        startServer(3, "127.0.0.1:" + workerPort, "--discovery-warmup-ms", "5000");
        await(() -> { call(3, MessageType.GET_COORDINATOR, "1", "probe", Map.of()); return true; }, 8000);
        Thread.sleep(1000); // let server 3 reach the Worker once, so only the warm-up keeps it ineligible
        var reply = call(3, MessageType.ELECTION, "1", "warm-up-election", Map.of());
        assertNotEquals(MessageType.ELECTION_OK, reply.type(), "a warming-up node must not promise to take over");
        assertEquals(false, reply.payload().get("eligible"));
        assertStableView(2, 1500, 1, 2);
        assertEquals(electionsBefore, countLines(serverLogs.get(2), "Election started id=2"),
            "coordinator must not start elections against an ineligible higher node");
        awaitCoordinator(3); // after warm-up the higher node still wins (Bully)
    }

    @Test void unresponsivePeersDoNotStarveLeaseRenewal() throws Exception {
        // Regression (LAN): a powered-off machine does not refuse TCP, every call to it waits for the full timeout.
        // Peer probes used to run sequentially on the lease-renewal thread, so a few dead peers delayed RENEW past
        // lease-ms and the live coordinator lost its lease. Black-hole peers accept TCP and never answer.
        var holes = new ArrayList<ServerSocket>();
        var held = new CopyOnWriteArrayList<Socket>();
        var acceptors = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var seeds = new ArrayList<String>();
            for (int peer = 11; peer <= 20; peer++) {
                var hole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()); holes.add(hole);
                seeds.add(peer + "@127.0.0.1:" + hole.getLocalPort());
                acceptors.submit(() -> { while (!hole.isClosed()) held.add(hole.accept()); return null; });
            }
            killServer(3);
            startServer(3, "127.0.0.1:" + workerPort, "--servers", String.join(",", seeds));
            awaitCoordinator(3);
            long generation = worker.status().getGeneration();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end) {
                var status = worker.status();
                assertEquals(3, status.getCoordinatorId());
                assertEquals(generation, status.getGeneration(), "coordinator lost its lease and re-claimed it");
                assertTrue(status.getLeaseRemainingMs() > 0, "lease expired while 10 peers were unresponsive");
                Thread.sleep(100);
            }
        } finally {
            for (var hole : holes) hole.close();
            for (var socket : held) socket.close();
            acceptors.shutdownNow();
        }
    }
}
