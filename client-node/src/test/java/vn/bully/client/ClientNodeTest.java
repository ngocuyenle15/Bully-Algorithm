package vn.bully.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import vn.bully.client.support.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Coordinator lookup from the client side, against fake servers that answer GET_COORDINATOR. */
class ClientNodeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<ServerSocket> fakes = new ArrayList<>();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private ClientNode client;

    /** A fake server that claims {@code id} (at its own address) is Coordinator with the given generation. */
    private int fake(int id, Object generation) throws IOException {
        var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        fakes.add(server);
        Map<String, Object> payload = Map.of("id", id, "host", "127.0.0.1", "port", server.getLocalPort(), "generation", generation);
        threads.submit(() -> {
            while (!server.isClosed()) {
                try (var socket = server.accept()) {
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    var reply = new TcpMessage(MessageType.COORDINATOR_INFO, "r", "fake", payload);
                    var out = socket.getOutputStream(); out.write(JSON.writeValueAsBytes(reply)); out.write('\n'); out.flush();
                } catch (IOException ignored) { }
            }
            return null;
        });
        return server.getLocalPort();
    }
    private ClientNode start(int first, int second) throws Exception {
        return client = new ClientNode(new Config(new String[]{"--headless", "--id", "t", "--bind-address", "127.0.0.1",
            "--servers", "1@127.0.0.1:" + first + ",2@127.0.0.1:" + second, "--worker", "127.0.0.1:1"}));
    }
    private void awaitCoordinator(String expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end && !expected.equals(client.snapshot().coordinator())) Thread.sleep(50);
        assertEquals(expected, client.snapshot().coordinator());
    }
    @AfterEach void stop() throws IOException {
        if (client != null) client.close();
        for (var server : fakes) server.close();
        threads.shutdownNow();
    }

    @Test void followsHighestGenerationNotFirstAnswer() throws Exception {
        // Server 1 is listed first but still remembers an old Coordinator (generation 3); server 2 knows generation 7.
        int stale = fake(1, 3), current = fake(2, 7);
        start(stale, current);
        awaitCoordinator("2@127.0.0.1:" + current);
    }

    @Test void malformedReplyDoesNotStopTheClient() throws Exception {
        int bad = fake(1, "not-a-number"), good = fake(2, 1);
        start(bad, good);
        awaitCoordinator("2@127.0.0.1:" + good);
    }
}
