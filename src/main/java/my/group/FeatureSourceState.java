package my.group;

import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Interpolation;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.paint.Color;
import kotlin.ranges.IntRange;
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
import net.imglib2.type.volatiles.VolatileDoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.imglib2.view.composite.RealComposite;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.RandomAccessibleIntervalDataSource;
import org.janelia.saalfeldlab.paintera.data.n5.VolatileWithSet;
import org.janelia.saalfeldlab.paintera.state.MinimalSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenuEntry;
import org.janelia.saalfeldlab.util.Colors;
import org.scijava.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FeatureSourceState<D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T>> extends MinimalSourceState<D, T, DataSource<D, T>, ARGBColorConverter<T>> {

    private interface Feature<D, T> {

        List<SourceState<? extends RealType<?>, ?>> dependsOn();

        DataSource<D, T> featureSource(String cacheDir, String name);

    }

    private static class GradientFeature<D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T>> implements Feature<D, T> {

        private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

        private final int dim;

        private final D d;

        private final SourceState<? extends RealType<?>, ?> dependsOn;

        public GradientFeature(
                final int dim,
                final D d,
                final SourceState<? extends RealType<?>, ?> dependsOn) {
            this.dim = dim;
            this.dependsOn = dependsOn;
            this.d = d;
        }

        @Override
        public List<SourceState<? extends RealType<?>, ?>> dependsOn() {
            return Collections.singletonList(dependsOn);
        }

        @Override
        public DataSource<D, T> featureSource(final String cacheDir, final String name) {
            final DataSource<? extends RealType<?>, ?> dataSource = dependsOn.getDataSource();
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
                    .cellDimensions(32, 32, 32)
                    .volatileAccesses(true);


            for (int lvl = 0; lvl < numLevels; ++lvl) {
                final RandomAccessibleInterval<D> raw = Converters.convert(
                        dataSource.getDataSource(0, lvl),
                        (src, tgt) -> tgt.setReal(src.getRealDouble()),
                        d.createVariable());
                final int nDim = raw.numDimensions();
                final RandomAccessible<D> rawExtended = Views.extendBorder(raw);
                final DiskCachedCellImgFactory<D> factory = new DiskCachedCellImgFactory<>(d, options);

                CellLoader<D> loader = img -> PartialDerivative.gradientCentralDifference(rawExtended, img, dim);

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
    }

    private static class MagnitudeFeature<D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T>> implements Feature<D, T> {

        private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

        private final D d;

        private final List<SourceState<? extends RealType<?>, ?>> dependsOn;

        public MagnitudeFeature(
            final D d,
            final SourceState<? extends RealType<?>, ?>... dependsOn) {
                this.dependsOn = Arrays.asList(dependsOn);
                this.d = d;
            }

            @Override
            public List<SourceState<? extends RealType<?>, ?>> dependsOn() {
                return new ArrayList<>(dependsOn);
            }

            @Override
            public DataSource<D, T> featureSource(final String cacheDir, final String name) {
                // TODO check consistency of all sources, as long as it is called only privately, do not care
                final DataSource<? extends RealType<?>, ?> dataSource = dependsOn.get(0).getDataSource();
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
                        .cellDimensions(32, 32, 32)
                        .volatileAccesses(true);


                for (int lvl = 0; lvl < numLevels; ++lvl) {
                    final RandomAccessibleInterval<D> raw = Converters.convert(
                            dataSource.getDataSource(0, lvl),
                            (src, tgt) -> tgt.setReal(src.getRealDouble()),
                            d.createVariable());
                    final DiskCachedCellImgFactory<D> factory = new DiskCachedCellImgFactory<>(d, options);
                    final int flvl = lvl;

                    CellLoader<D> loader = img -> {
                        for (SourceState<? extends RealType<?>, ?> state : dependsOn) {
                            LOG.trace("Adding square of state {} with type {}", state.nameProperty().get(), state.getDataSource().getDataType());
                            LoopBuilder
                                    .setImages(Views.interval(state.getDataSource().getDataSource(0, flvl), img), img)
                                    .forEachPixel((src, tgt) -> tgt.setReal(tgt.getRealDouble() + src.getRealDouble() * src.getRealDouble()));
                        }
                        LOG.trace("Taking sqrt");
                        img.forEach(px -> px.setReal(Math.sqrt(px.getRealDouble())));
                        LOG.trace("First voxel value {}", img.cursor().next());
                    };

                    data[lvl] = factory.create(raw, loader, options);
                    vdata[lvl] = VolatileViews.wrapAsVolatile(data[lvl]);
                }
                return new RandomAccessibleIntervalDataSource<>(
                        data,
                        vdata,
                        tfs,
                        () -> {
                        },
                        new InterpolationFunc<>(),
                        new InterpolationFunc<>(),
                        name);
        }
    }

    private static class HessianEigenvalueFeature<D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T>> implements Feature<D, T> {

        private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

        private final D d;

        private final List<SourceState<? extends RealType<?>, ?>> dependsOn;

        public HessianEigenvalueFeature(
                final D d,
                final SourceState<? extends RealType<?>, ?>... dependsOn) {
            this.dependsOn = Arrays.asList(dependsOn);
            this.d = d;
        }

        @Override
        public List<SourceState<? extends RealType<?>, ?>> dependsOn() {
            return new ArrayList<>(dependsOn);
        }

        @Override
        public DataSource<D, T> featureSource(final String cacheDir, final String name) {
            // TODO check consistency of all sources, as long as it is called only privately, do not care
            final DataSource<? extends RealType<?>, ?> dataSource = dependsOn.get(0).getDataSource();
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
                    .cellDimensions(32, 32, 32)
                    .volatileAccesses(true);


            for (int lvl = 0; lvl < numLevels; ++lvl) {
                final RandomAccessibleInterval<D> raw = Converters.convert(
                        dataSource.getDataSource(0, lvl),
                        (src, tgt) -> tgt.setReal(src.getRealDouble()),
                        d.createVariable());
                final DiskCachedCellImgFactory<D> factory = new DiskCachedCellImgFactory<>(d, options);
                final int flvl = lvl;

                CellLoader<D> loader = img -> {
                    for (SourceState<? extends RealType<?>, ?> state : dependsOn) {
                        LOG.trace("Adding square of state {} with type {}", state.nameProperty().get(), state.getDataSource().getDataType());
                        LoopBuilder
                                .setImages(Views.interval(state.getDataSource().getDataSource(0, flvl), img), img)
                                .forEachPixel((src, tgt) -> tgt.setReal(tgt.getRealDouble() + src.getRealDouble() * src.getRealDouble()));
                    }
                    LOG.trace("Taking sqrt");
                    img.forEach(px -> px.setReal(Math.sqrt(px.getRealDouble())));
                    LOG.trace("First voxel value {}", img.cursor().next());
                };

                data[lvl] = factory.create(raw, loader, options);
                vdata[lvl] = VolatileViews.wrapAsVolatile(data[lvl]);
            }
            return new RandomAccessibleIntervalDataSource<>(
                    data,
                    vdata,
                    tfs,
                    () -> {
                    },
                    new InterpolationFunc<>(),
                    new InterpolationFunc<>(),
                    name);
        }
    }


    private final Feature<D, T> feature;

    private FeatureSourceState(
            final Feature<D, T> feature,
            final String name,
            final String cacheDir) {
        super(
                feature.featureSource(cacheDir, name),
                new ARGBColorConverter.InvertingImp1<T>(),
                new ARGBCompositeAlphaAdd(),
                name,
                feature.dependsOn().stream().toArray(SourceState[]::new));
        this.feature = feature;
        converter().setMin(0.0);
        converter().setMax(50.0);
        converter().setColor(Colors.toARGBType(Color.MAGENTA));
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
                    final SourceState<? extends RealType<?>, ?> raw = comboBox.getValue();
                    final int nDim = raw.getDataSource().getDataSource(0, 0).numDimensions();
                    final ChannelFeatureSourceState.GradientFeature<DoubleType, VolatileDoubleType> gradientFeature = new ChannelFeatureSourceState.GradientFeature<>(new DoubleType(), raw, directory);
                    ChannelFeatureSourceState<DoubleType, VolatileDoubleType, RealComposite<DoubleType>, VolatileWithSet<RealComposite<VolatileDoubleType>>> gradientState = new ChannelFeatureSourceState<>(gradientFeature, "gradients", directory);
                    final FeatureSourceState<? extends RealType<?>, ?>[] gradients = IntStream
                            .range(0, nDim)
                            .mapToObj(dim -> new GradientFeature<DoubleType, VolatileDoubleType>(dim, new DoubleType(), raw))
                            .map(feat -> new FeatureSourceState(feat, raw.nameProperty().getName() + "-gradient", directory))
                            .toArray(FeatureSourceState[]::new);
                    final FeatureSourceState<DoubleType, VolatileDoubleType> magnitude = new FeatureSourceState<DoubleType, VolatileDoubleType>(
                            new MagnitudeFeature<DoubleType, VolatileDoubleType>(new DoubleType(), gradients),
                            raw.nameProperty().getName() + "-gradient-magnitude",
                            directory);
                    gradients[0].converter().colorProperty().set(Colors.toARGBType("#ff0000"));
                    gradients[1].converter().colorProperty().set(Colors.toARGBType("#00ff00"));
                    gradients[2].converter().colorProperty().set(Colors.toARGBType("#0000ff"));
                    Stream.of(gradients).forEach(pbv::addState);
                    Stream.of(magnitude).forEach(pbv::addState);
                    Stream.of(gradientState).forEach(pbv::addState);
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
