package vn.bully.server;

import javafx.application.Platform;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.stage.Stage;
import vn.bully.common.FxSupport;

public final class  ServerView {
    public static void open(ServerNode node, Runnable stop) {
        Platform.startup(() -> {
            var info = new TextArea(); info.setEditable(false); info.setPrefRowCount(13);
            var button = new Button("Stop Server (simulate crash)");
            var root = new VBox(10, info, button, FxSupport.logs(node.log)); root.setPadding(new Insets(16));
            var timer = new Timeline(new KeyFrame(Duration.seconds(0.5), e -> info.setText(node.display())));
            timer.setCycleCount(Animation.INDEFINITE); timer.play();
            var stage = new Stage(); stage.setTitle("Server " + node.id()); stage.setScene(new Scene(root, 820, 620));
            button.setOnAction(e -> { timer.stop(); stop.run(); stage.close(); });
            stage.setOnCloseRequest(e -> { timer.stop(); stop.run(); }); stage.show();
        });
    }
}
