package vn.bully.worker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import vn.bully.common.*;
import javafx.application.Platform;
import javafx.stage.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

@EnabledIfSystemProperty(named = "bully.ui", matches = "true")
class WorkerViewTest {
    @Test void opensAndRendersApplication() throws Exception {
        var log = new EventLog("UI-Worker");
            var state = new WorkerState(5000, 60000, log);
            long generation = state.control(vn.bully.common.rpc.ControlRequest.newBuilder().setAction("CLAIM").setCoordinatorId(3).build()).getGeneration();
            state.control(vn.bully.common.rpc.ControlRequest.newBuilder().setAction("RESERVE").setCoordinatorId(3).setGeneration(generation).setClientId("client-demo").setRequestId("demo-request").build());
            WorkerView.open(state, log, () -> {});
        var done = new CountDownLatch(1); var error = new AtomicReference<Throwable>();
        try {
            Thread.sleep(1200);
            Platform.runLater(() -> {
                try {
                    var stage = (Stage) Window.getWindows().stream().filter(Window::isShowing).findFirst().orElseThrow();
                    stage.hide();
                    var root = stage.getScene().getRoot(); root.applyCss(); root.layout();
                    
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
        } finally {  }
    }
}
