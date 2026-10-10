package vn.bully.server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import vn.bully.server.support.*;
import javafx.application.Platform;
import javafx.stage.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

@EnabledIfSystemProperty(named = "bully.ui", matches = "true")
class ServerViewTest {
    @Test void opensAndRendersApplication() throws Exception {
        var worker = io.grpc.ServerBuilder.forPort(0).addService(new vn.bully.server.rpc.DocumentWorkerGrpc.DocumentWorkerImplBase() {
                @Override public void control(vn.bully.server.rpc.ControlRequest r, io.grpc.stub.StreamObserver<vn.bully.server.rpc.WorkerReply> out) {
                    out.onNext(vn.bully.server.rpc.WorkerReply.newBuilder().setAccepted(true).setCoordinatorId(3).setGeneration(1).setLeaseRemainingMs(5000).setWorkerStatus("AVAILABLE").build()); out.onCompleted();
                }
            }).build().start();
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            var node = new ServerNode(new Config(new String[]{"--id","3","--servers","3@127.0.0.1:" + port,"--worker","127.0.0.1:" + worker.getPort(),"--election-ms","100"}));
            ServerView.open(node, () -> {});
        var done = new CountDownLatch(1); var error = new AtomicReference<Throwable>();
        try {
            Thread.sleep(1200);
            Platform.runLater(() -> {
                try {
                    var stage = (Stage) Window.getWindows().stream().filter(Window::isShowing).findFirst().orElseThrow();
                    stage.hide();
                    var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
                    assertTrue(root.lookupAll(".button").stream().anyMatch(n -> n instanceof javafx.scene.control.Button b && b.getText().contains("Stop Server")));
                    var snapshot = root.snapshot(null, null);
                    assertTrue(snapshot.getWidth() > 500); assertTrue(snapshot.getHeight() > 300);
                    var image = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                        image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                    ImageIO.write(image, "png", Path.of("target", "ui-preview.png").toFile());
                } catch (Throwable e) { error.set(e); }
                finally { Platform.exit(); done.countDown(); }
            });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError("UI check failed", error.get());
        } finally { node.close(); worker.shutdownNow(); }
    }
}
