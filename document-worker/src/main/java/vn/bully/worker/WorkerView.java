package vn.bully.worker;

import javafx.application.Platform;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.stage.Stage;
import vn.bully.worker.support.*;

public final class WorkerView {
    public static void open(WorkerState state, EventLog log, Runnable stop) {
        Platform.startup(() -> {
            var status = new Label();
            var root = new VBox(12, status, FxSupport.logs(log)); root.setPadding(new Insets(16));
            var timer = new Timeline(new KeyFrame(Duration.seconds(0.5), event -> {
                var s = state.status();
                status.setText("Worker: " + s.getWorkerStatus() + "\nCoordinator: " + s.getCoordinatorId()
                    + "  generation: " + s.getGeneration() + "\nClient: " + s.getClientId()
                    + "\nSession: " + s.getSessionId() + "\nRemaining: " + s.getSessionRemainingMs() / 1000
                    + " s\nOperation: " + s.getOperation());
            })); timer.setCycleCount(Animation.INDEFINITE); timer.play();
            var stage = new Stage(); stage.setTitle("Document Worker"); stage.setScene(new Scene(root, 760, 460));
            stage.setOnCloseRequest(e -> { timer.stop(); stop.run(); }); stage.show();
        });
    }
}
