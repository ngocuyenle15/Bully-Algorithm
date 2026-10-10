package vn.bully.worker;

import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import vn.bully.worker.support.*;
import java.util.concurrent.*;

public final class WorkerApplication {
    public static void main(String[] args) throws Exception {
        var config = new Config(args);
        var log = new EventLog("Worker");
        var state = new WorkerState(config.number("lease-ms", 5000), config.number("session-ms", 60000), log);
        var address = new InetSocketAddress(config.bindAddress(), config.port("port", 9100, false));
        var server = NettyServerBuilder.forAddress(address).maxInboundMessageSize(64 * 1024 * 1024)
            .addService(new DocumentServiceImpl(state, log)).build().start();
        var timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(state::status, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { timer.shutdownNow(); server.shutdownNow(); }));
        log.info("Worker started listening=" + address);
        if (!config.headless()) WorkerView.open(state, log, () -> { timer.shutdownNow(); server.shutdownNow(); });
        server.awaitTermination();
    }
}
