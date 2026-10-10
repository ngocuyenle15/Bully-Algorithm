package vn.bully.client;

import javafx.application.Platform;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Insets;
import javafx.stage.*;
import vn.bully.common.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public final class ClientView {
    public static void open(ClientNode node, Runnable stop) {
        Platform.startup(() -> {
            var tasks = Executors.newVirtualThreadPerTaskExecutor();
            var stage = new Stage(); var info = new Label();
            var request = new Button("Request Access"); var cancel = new Button("Cancel Request"); var release = new Button("Release Access");
            request.setOnAction(e -> node.requestAccess()); cancel.setOnAction(e -> node.cancelRequest()); release.setOnAction(e -> node.releaseAccess());
            var operation = new ComboBox<String>(); operation.getItems().addAll("Merge", "Split", "Watermark"); operation.setValue("Merge");
            var chosen = new ArrayList<Path>(); var fileInfo = new TextArea(); fileInfo.setEditable(false); fileInfo.setPrefRowCount(3);
            var select = new Button("Select PDF files");
            select.setOnAction(e -> {
                var chooser = new FileChooser(); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
                var files = chooser.showOpenMultipleDialog(stage);
                if (files != null) { chosen.clear(); files.stream().map(File::toPath).forEach(chosen::add);
                    fileInfo.setText(String.join("\n", chosen.stream().map(Path::toString).toList())); }
            });
            var from = new TextField("1"); from.setPrefColumnCount(4);
            var to = new TextField("1"); to.setPrefColumnCount(4);
            var watermark = new TextField(); watermark.setPromptText("Watermark text");
            var process = new Button("Process and save result");
            process.setOnAction(e -> {
                try {
                    String op = operation.getValue();
                    int first = op.equals("Split") ? Integer.parseInt(from.getText()) : 1;
                    int last = op.equals("Split") ? Integer.parseInt(to.getText()) : 1;
                    String text = watermark.getText(); var files = List.copyOf(chosen);
                    if (files.isEmpty()) throw new IllegalArgumentException("Select PDF files first");
                    var chooser = new FileChooser(); chooser.setInitialFileName(op.toLowerCase() + ".pdf");
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
                    File destination = chooser.showSaveDialog(stage);
                    if (destination == null) return;
                    process.setDisable(true);
                    tasks.submit(() -> {
                        try { Files.write(destination.toPath(), node.process(op, files, first, last, text));
                            node.log.info("Saved result: " + destination); }
                        catch (Exception error) { Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, error.getMessage()).show()); }
                    });
                } catch (Exception error) { new Alert(Alert.AlertType.ERROR, error.getMessage()).show(); }
            });
            var controls = new VBox(8, new HBox(8, operation, select), fileInfo,
                new HBox(8, new Label("From page"), from, new Label("To page"), to), watermark, process);
            var root = new VBox(12, info, new HBox(8, request, cancel, release), new Separator(), controls, FxSupport.logs(node.log));
            root.setPadding(new Insets(16));
            Runnable refresh = () -> {
                var s = node.snapshot();
                info.setText("Client: " + s.clientId() + " | " + s.username() + "\nConnection: " + s.connection()
                    + " | Coordinator: " + s.coordinator() + "\nState: " + s.state() + " | Queue: " + s.position()
                    + "\nSession: " + s.sessionId() + " | Remaining: " + s.remainingMs() / 1000 + " s");
                boolean active = !s.sessionId().isEmpty();
                request.setDisable(!s.requestId().isEmpty()); cancel.setDisable(s.requestId().isEmpty() || active);
                release.setDisable(!active || s.processing() || s.state().equals("RELEASING"));
                controls.setDisable(!active || s.processing() || s.state().equals("RELEASING"));
                process.setDisable(!active || s.processing());
                from.setDisable(!operation.getValue().equals("Split")); to.setDisable(!operation.getValue().equals("Split"));
                watermark.setDisable(!operation.getValue().equals("Watermark"));
            };
            refresh.run(); // Enforce access controls before the first JavaFX pulse.
            var timer = new Timeline(new KeyFrame(Duration.seconds(0.3), e -> refresh.run()));
            timer.setCycleCount(Animation.INDEFINITE); timer.play();
            stage.setTitle("Client " + node.snapshot().clientId()); stage.setScene(new Scene(root, 850, 730));
            stage.setOnCloseRequest(e -> { timer.stop(); tasks.shutdownNow(); stop.run(); }); stage.show();
        });
    }
}
