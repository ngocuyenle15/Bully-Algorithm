package vn.bully.worker;

import io.grpc.ServerBuilder;
import vn.bully.worker.support.*;
import java.util.concurrent.*;

public final class WorkerApplication {
    public static void main(String[] args) throws Exception {
        var config = new Config(args);
        var log = new EventLog("Worker");
        var state = new WorkerState(config.number("lease-ms", 5000), config.number("session-ms", 60000), log);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var server = ServerBuilder.forPort(config.number("port", 9100)).maxInboundMessageSize(64 * 1024 * 1024)
            .addService(new DocumentServiceImpl(state, log)).build().start();
        timer.scheduleAtFixedRate(state::status, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { timer.shutdownNow(); server.shutdownNow(); }));
        log.info("Worker started port=" + server.getPort());
        if (!config.headless()) WorkerView.open(state, log, () -> { timer.shutdownNow(); server.shutdownNow(); });
        server.awaitTermination();
    }
}
