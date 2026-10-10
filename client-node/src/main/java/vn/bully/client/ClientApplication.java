package vn.bully.client;

import vn.bully.common.Config;
import java.util.concurrent.CountDownLatch;

public final class ClientApplication {
    public static void main(String[] args) throws Exception {
        var config = new Config(args);
        var node = new ClientNode(config);
        var done = new CountDownLatch(1);
        Runnable stop = () -> { node.close(); done.countDown(); };
        Runtime.getRuntime().addShutdownHook(new Thread(stop));
        if (config.headless()) {
            if (Boolean.parseBoolean(config.get("request", "false"))) node.requestAccess();
        } else ClientView.open(node, stop);
        done.await();
    }
}
