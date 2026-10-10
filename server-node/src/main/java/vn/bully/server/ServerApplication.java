package vn.bully.server;

import vn.bully.common.Config;
import java.util.concurrent.CountDownLatch;

public final class ServerApplication {
    public static void main(String[] args) throws Exception {
        var config = new Config(args);
        var node = new ServerNode(config);
        var done = new CountDownLatch(1);
        Runnable stop = () -> { node.close(); done.countDown(); };
        Runtime.getRuntime().addShutdownHook(new Thread(stop));
        if (!config.headless()) ServerView.open(node, stop);
        done.await();
    }
}
