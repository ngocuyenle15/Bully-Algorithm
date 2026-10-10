package vn.bully.common;

import javafx.application.Platform;
import javafx.scene.control.TextArea;

public final class FxSupport {
    private FxSupport() {}
    public static TextArea logs(EventLog log) {
        var area = new TextArea(); area.setEditable(false); area.setPrefRowCount(12);
        log.listen(line -> Platform.runLater(() -> {
            area.appendText(line + "\n");
            if (area.getLength() > 40000) area.deleteText(0, 10000);
        }));
        return area;
    }
}
