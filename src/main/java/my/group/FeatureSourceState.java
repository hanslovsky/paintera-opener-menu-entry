package my.group;

import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Interpolation;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.paint.Color;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gradient.PartialDerivative;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.DiskCachedCellImgFactory;
import net.imglib2.cache.img.DiskCachedCellImgOptions;
import net.imglib2.converter.ARGBColorConverter;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.volatiles.AbstractVolatileRealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.RandomAccessibleIntervalDataSource;
import org.janelia.saalfeldlab.paintera.state.MinimalSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenuEntry;
import org.janelia.saalfeldlab.util.Colors;
import org.scijava.plugin.Plugin;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class FeatureSourceState<D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T>> extends MinimalSourceState<D, T, DataSource<D, T>, ARGBColorConverter<T>> {

    public FeatureSourceState(
            final SourceState<? extends RealType<?>, ?> underlyingState,
            final int[] blockSize,
            final String name,
            final D d,
            final String cacheDir) {
        super(
                makeFeatureSource(underlyingState.getDataSource(), blockSize, d, cacheDir, name),
                new ARGBColorConverter.InvertingImp1<T>(),
                new ARGBCompositeAlphaAdd(),
                name,
                underlyingState);
        converter().setMin(0.0);
        converter().setMax(50.0);
        converter().setColor(Colors.toARGBType(Color.MAGENTA));
    }

    private static  <D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T>> DataSource<D, T> makeFeatureSource(
            final DataSource<? extends RealType<?>, ?> dataSource,
            final int[] blockSize,
            final D d,
            final String cacheDir,
            final String name
    ) {
        final int numLevels = dataSource.getNumMipmapLevels();
        final AffineTransform3D[] tfs = IntStream
                .range(0, numLevels)
                .mapToObj(lvl -> { AffineTransform3D tf = new AffineTransform3D(); dataSource.getSourceTransform(0, lvl, tf); return tf;})
                .toArray(AffineTransform3D[]::new);

        final RandomAccessibleInterval<D>[] data = new RandomAccessibleInterval[numLevels];
        final RandomAccessibleInterval<T>[] vdata = new RandomAccessibleInterval[numLevels];

        final DiskCachedCellImgOptions options = DiskCachedCellImgOptions
                .options()
                .tempDirectory(Paths.get(cacheDir))
                .tempDirectoryPrefix("gradient-")
                .deleteCacheDirectoryOnExit(true)
                .volatileAccesses(true);


        for (int lvl = 0; lvl < numLevels; ++lvl) {
            final RandomAccessibleInterval<D> raw = Converters.convert(
                    dataSource.getDataSource(0, lvl),
                    (src, tgt) -> tgt.setReal(src.getRealDouble()),
                    d.createVariable());
            final int nDim = raw.numDimensions();
            final RandomAccessible<D> rawExtended = Views.extendBorder(raw);
            final DiskCachedCellImgFactory<D> factory = new DiskCachedCellImgFactory<>(d, options);

            CellLoader<D> loader = img -> {

                final ArrayImg<D, ?> diff = new ArrayImgFactory<>(d).create(img);
                for (int dim = 0; dim < nDim; ++dim) {
                    img.forEach(D::setZero);
                    PartialDerivative.gradientCentralDifference(rawExtended, Views.translate(diff, Intervals.minAsLongArray(img)), dim);
                    LoopBuilder.setImages(diff, img).forEachPixel((src, tgt) -> {
                        final double val = src.getRealDouble();
                        tgt.setReal(tgt.getRealDouble() + val * val);
                    });
                }
                img.forEach(px -> px.setReal(Math.sqrt(px.getRealDouble())));
            };

            data[lvl] = factory.create(raw, loader, options);
            vdata[lvl] = VolatileViews.wrapAsVolatile(data[lvl]);
        }
        return new RandomAccessibleIntervalDataSource<D, T>(
                data,
                vdata,
                tfs,
                () -> {},
                new InterpolationFunc<>(),
                new InterpolationFunc<>(),
                name);
    }

    private static class InterpolationFunc<D extends NumericType<D>> implements Function<Interpolation, InterpolatorFactory<D, RandomAccessible<D>>> {

        @Override
        public InterpolatorFactory<D, RandomAccessible<D>> apply(Interpolation interpolation) {
            if (interpolation == Interpolation.NLINEAR)
                return new NLinearInterpolatorFactory<>();
            else
                return new NearestNeighborInterpolatorFactory<>();
        }
    }

    @Plugin(type = OpenDialogMenuEntry.class,
            menuPath = "_Features>_Gradient Magnitude")
    public static class MenuEntry implements OpenDialogMenuEntry {

        @Override
        public BiConsumer<PainteraBaseView, String> onAction() {
            return (pbv, directory) -> {
                final List<SourceState<? extends RealType<?>, ?>> sources = pbv
                        .sourceInfo()
                        .trackSources()
                        .stream()
                        .map(pbv.sourceInfo()::getState)
                        .filter(state -> state.getDataSource().getDataType() instanceof RealType<?>)
                        .map(s -> (SourceState<? extends RealType<?>, ?>)s)
                        .collect(Collectors.toList());
                final Alert alert = PainteraAlerts.alert(Alert.AlertType.CONFIRMATION, true);
                final ObservableList<SourceState<? extends RealType<?>, ?>> observableSources = FXCollections.observableArrayList(sources);
                final ComboBox<SourceState<? extends RealType<?>, ?>> comboBox = new ComboBox<>(observableSources);
                alert.getDialogPane().setContent(comboBox);
                final Optional<ButtonType> bt = alert.showAndWait();
                if (bt.filter(ButtonType.OK::equals).isPresent() && comboBox.getValue() != null) {
                    FeatureSourceState<DoubleType, ?> state = new FeatureSourceState<>(
                            comboBox.getValue(),
                            new int[]{32, 32, 32},
                            "gradient magnitude",
                            new DoubleType(),
                            directory);
                    pbv.addState(state);
                }
            };
        }
    }

    @Override
    public void onAdd(PainteraBaseView paintera) {
        converter().minProperty().addListener(obs -> paintera.orthogonalViews().requestRepaint());
        converter().maxProperty().addListener(obs -> paintera.orthogonalViews().requestRepaint());
        converter().colorProperty().addListener(obs -> paintera.orthogonalViews().requestRepaint());
        converter().alphaProperty().addListener(obs -> paintera.orthogonalViews().requestRepaint());
    }



}
