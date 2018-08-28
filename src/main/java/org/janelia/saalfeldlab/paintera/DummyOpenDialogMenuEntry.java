package org.janelia.saalfeldlab.paintera;

import com.sun.javafx.application.PlatformImpl;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.janelia.saalfeldlab.fx.ui.Exceptions;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenu;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenuEntry;

import java.util.function.BiConsumer;

@OpenDialogMenuEntry.OpenDialogMenuEntryPath(path = "sort>of>ok")
public class DummyOpenDialogMenuEntry implements OpenDialogMenuEntry {


    @Override
    public BiConsumer<PainteraBaseView, String> onAction() {
        return (pbv, pd) -> Exceptions.exceptionAlert(Paintera.NAME,"LOL DUMMY!", new RuntimeException("FOR SHOW!")).show();
    }


    public static void main(String[] args) {
        PlatformImpl.startup(() -> {
        });

        OpenDialogMenu odm = new OpenDialogMenu(Exception::printStackTrace);
        ContextMenu menu = odm.getContextMenu("SOME MENU!", null, null);


        Platform.runLater(() -> {
            Stage stage = new Stage();
            StackPane root = new StackPane();
            root.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> menu.show(root, e.getScreenX(), e.getScreenY()));
            Scene scene = new Scene(root, 800, 600);
            stage.setScene(scene);
            stage.show();
        });


    }
}
