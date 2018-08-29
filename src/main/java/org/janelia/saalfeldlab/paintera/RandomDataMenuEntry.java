package org.janelia.saalfeldlab.paintera;

import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Interpolation;
import com.sun.javafx.application.PlatformImpl;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.converter.ARGBColorConverter;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileDoubleArray;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.volatiles.VolatileDoubleType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import org.janelia.saalfeldlab.fx.ui.Exceptions;
import org.janelia.saalfeldlab.fx.ui.NumberField;
import org.janelia.saalfeldlab.fx.ui.ObjectField;
import org.janelia.saalfeldlab.paintera.composition.CompositeCopy;
import org.janelia.saalfeldlab.paintera.data.RandomAccessibleIntervalDataSource;
import org.janelia.saalfeldlab.paintera.state.RawSourceState;
import org.janelia.saalfeldlab.paintera.ui.opendialog.VolatileHelpers;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenu;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenuEntry;
import org.scijava.plugin.Plugin;

import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Plugin(type = OpenDialogMenuEntry.class, menuPath = "this>is>a>random>dataset")
public class RandomDataMenuEntry implements OpenDialogMenuEntry {


    @Override
    public BiConsumer<PainteraBaseView, String> onAction() {

        return (pbv, pd) -> {
            double prefWidth = 100;
            ObjectField<String, StringProperty> name = ObjectField.stringField("random", ObjectField.SubmitOn.FOCUS_LOST, ObjectField.SubmitOn.ENTER_PRESSED);
            NumberField<LongProperty> dimX = NumberField.longField(1, d -> d > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            NumberField<LongProperty> dimY = NumberField.longField(1, d -> d > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            NumberField<LongProperty> dimZ = NumberField.longField(1, d -> d > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            NumberField<DoubleProperty> min = NumberField.doubleField(0.0, d -> true, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            NumberField<DoubleProperty> max = NumberField.doubleField(1.0, d -> true, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);

            min.valueProperty().addListener((obs, oldv, newv) -> {
                if (newv.doubleValue() == max.valueProperty().get())
                    min.valueProperty().set(oldv.doubleValue());
            });

            max.valueProperty().addListener((obs, oldv, newv) -> {
                if (newv.doubleValue() == min.valueProperty().get())
                    max.valueProperty().set(oldv.doubleValue());
            });

            name.textField().setPrefWidth(prefWidth);
            dimX.textField().setPrefWidth(prefWidth);
            dimY.textField().setPrefWidth(prefWidth);
            dimZ.textField().setPrefWidth(prefWidth);
            min.textField().setPrefWidth(prefWidth);
            max.textField().setPrefWidth(prefWidth);

            Label n = new Label("name");
            Label x = new Label("dim x");
            Label y = new Label("dim y");
            Label z = new Label("dim z");
            Label minLabel = new Label("min");
            Label maxLabel = new Label("max");

            Region fillerN = new Region();
            Region fillerX = new Region();
            Region fillerY = new Region();
            Region fillerZ = new Region();
            Region fillerMin = new Region();
            Region fillerMax = new Region();

            HBox.setHgrow(fillerN, Priority.ALWAYS);
            HBox.setHgrow(fillerX, Priority.ALWAYS);
            HBox.setHgrow(fillerY, Priority.ALWAYS);
            HBox.setHgrow(fillerZ, Priority.ALWAYS);
            HBox.setHgrow(fillerMin, Priority.ALWAYS);
            HBox.setHgrow(fillerMax, Priority.ALWAYS);

            VBox contents = new VBox(
                    new HBox(n, fillerN, name.textField()),
                    new HBox(x, fillerX, dimX.textField()),
                    new HBox(y, fillerY, dimY.textField()),
                    new HBox(z, fillerZ, dimZ.textField()),
                    new HBox(minLabel, fillerMin, min.textField()),
                    new HBox(maxLabel, fillerMax, max.textField())
            );

            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            dialog.setTitle(Paintera.NAME);
            dialog.setHeaderText("Add random data");
            dialog.getDialogPane().setContent(contents);
            dialog.setResizable(true);

            ButtonType button = dialog.showAndWait().orElse(ButtonType.CANCEL);

            if (ButtonType.OK.equals(button))
            {
                long[] dims ={dimX.valueProperty().get(), dimY.valueProperty().get(), dimZ.valueProperty().get()};
                int[] blockSize = {64, 64, 64};
                CellGrid grid = new CellGrid(dims, blockSize);

                final double m = min.valueProperty().doubleValue();
                final double M = max.valueProperty().doubleValue();
                final double r = M - m;

                CellLoader<DoubleType> loader = img -> {
                    long[] tl = Intervals.minAsLongArray(img);
                    long seed = IntervalIndexer.positionToIndex(tl, dims);
                    Random rng = new Random(seed);
                    img.forEach(d -> d.set(rng.nextDouble() * r + m));
                };
                Cache<Long, Cell<VolatileDoubleArray>> cache = new SoftRefLoaderCache<Long, Cell<VolatileDoubleArray>>()
                        .withLoader(LoadedCellCacheLoader.get(grid, loader, new DoubleType(), AccessFlags.setOf(AccessFlags.VOLATILE)));
                CachedCellImg<DoubleType, VolatileDoubleArray> img = new CachedCellImg<DoubleType, VolatileDoubleArray>(grid, new DoubleType(), cache, new VolatileDoubleArray(1, true));
                RandomAccessibleInterval<VolatileDoubleType> vimg = VolatileViews.wrapAsVolatile(img, pbv.getQueue());
                AffineTransform3D[] transform = {new AffineTransform3D()};
                RandomAccessibleInterval<DoubleType>[] data = new RandomAccessibleInterval[]{img};
                RandomAccessibleInterval<VolatileDoubleType>[] vdata = new RandomAccessibleInterval[]{vimg};

                RandomAccessibleIntervalDataSource<DoubleType, VolatileDoubleType> source = new RandomAccessibleIntervalDataSource<>(data, vdata, transform, interpolate(), interpolate(), name.valueProperty().get());

                RawSourceState<DoubleType, VolatileDoubleType> rawState = new RawSourceState<>(
                        source,
                        new ARGBColorConverter.Imp0<>(m, M),
                        new CompositeCopy<>(),
                        source.getName()
                );

                pbv.addRawSource(rawState);
            }

        };
    }

    private static <T extends NumericType<T>> Function<Interpolation, InterpolatorFactory<T, RandomAccessible<T>>> interpolate()
    {
        return i -> Interpolation.NLINEAR.equals(i) ? new NLinearInterpolatorFactory<>() : new NearestNeighborInterpolatorFactory<>();
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
