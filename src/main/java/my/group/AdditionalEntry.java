package my.group;

import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenuEntry;
import org.scijava.plugin.Plugin;
import java.util.function.BiConsumer;

@Plugin(type = OpenDialogMenuEntry.class, menuPath = "_My>_Menu>_Entry")
public class AdditionalEntry implements OpenDialogMenuEntry {
    @Override
    public BiConsumer<PainteraBaseView, String> onAction() {
        return (pbv, string) -> System.out.println("Additional entry!");
    }
}

