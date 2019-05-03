package my.group;

import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Interpolation;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.algorithm.morphology.distance.DistanceTransform;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.converter.ARGBColorConverter;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealDoubleConverter;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileDoubleArray;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.PrimitiveType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.volatiles.VolatileDoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.util.ValueTriple;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.fx.Labels;
import org.janelia.saalfeldlab.fx.TitledPanes;
import org.janelia.saalfeldlab.fx.ui.NumberField;
import org.janelia.saalfeldlab.fx.ui.ObjectField;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.cache.InvalidateAll;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.RandomAccessibleIntervalDataSource;
import org.janelia.saalfeldlab.paintera.state.MinimalSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.janelia.saalfeldlab.paintera.ui.BindUnbindAndNodeSupplier;
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenuEntry;
import org.janelia.saalfeldlab.paintera.ui.source.state.SourceStateUIElementsDefaultFactory;
import org.scijava.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class DistanceTransformSourceState extends MinimalSourceState<DoubleType, VolatileDoubleType, DistanceTransformSourceState.DistanceTransformSource, ARGBColorConverter<VolatileDoubleType>> {

    public static class DistanceTransformSource implements DataSource<DoubleType, VolatileDoubleType> {

        private final DataSource<? extends RealType<?>, ?> sampledFunction;

        private final String name;

        private int[] blockSize = {16, 16, 16};

        private IntFunction<CellLoader<DoubleType>> loaderFactory = null;

        private RandomAccessibleIntervalDataSource<DoubleType, VolatileDoubleType> distanceTransform;

        private DistanceTransformSource(
                final DataSource<? extends RealType<?>, ?> sampledFunction,
                final String name) {
            this.sampledFunction = sampledFunction;
            setLoaderFactory(sf -> level ->  img -> {
                final int[] halo = {0, 0, 0};
                final DoubleType sampleExtension = new DoubleType(Math.sqrt(3*256));
                final FinalInterval withContext = Intervals.expand(img, new FinalDimensions(halo));
                final RandomAccessibleInterval<DoubleType> convertedSampledFunction = Converters.convert(sf.getDataSource(0, level), new RealDoubleConverter(), new DoubleType());
                final IntervalView<DoubleType> sampled = Views.interval(Views.extendValue(convertedSampledFunction, sampleExtension.copy()), withContext);
                final RandomAccessibleInterval<DoubleType> dt = ArrayImgs.doubles(Intervals.dimensionsAsLongArray(withContext));
                DistanceTransform.transform(
                        Views.zeroMin(sampled),
                        dt,
                        DistanceTransform.DISTANCE_TYPE.EUCLIDIAN);
                LoopBuilder
                        .setImages(img, Views.interval(Views.translate(dt, Intervals.minAsLongArray(withContext)), img))
                        .forEachPixel(DoubleType::set);
            });
            this.name = name;
        }

        public synchronized void setLoaderFactory(final Function<DataSource<? extends RealType<?>, ?>, IntFunction<CellLoader<DoubleType>>> loaderFactory) {
            this.loaderFactory = loaderFactory.apply(this.sampledFunction);
            update();
        }

        public synchronized void setBlockSize(final int... blockSize) {
            this.blockSize = blockSize.clone();
            update();
        }

        private synchronized void update() {

            final AffineTransform3D[] transforms = IntStream
                    .range(0, getNumMipmapLevels())
                    .mapToObj(level -> {
                        AffineTransform3D tf = new AffineTransform3D();
                        sampledFunction.getSourceTransform(0, level, tf);
                        return tf;
                    })
                    .toArray(AffineTransform3D[]::new);

            final RandomAccessibleInterval<DoubleType>[] data = IntStream
                    .range(0, getNumMipmapLevels())
                    .mapToObj(level -> {
                        final CellGrid grid = new CellGrid(Intervals.dimensionsAsLongArray(sampledFunction.getDataSource(0, level)), blockSize);
                        final CellLoader<DoubleType> loader = loaderFactory.apply(level);
                        final SoftRefLoaderCache<Long, Cell<VolatileDoubleArray>> cache = new SoftRefLoaderCache<>();
                        final LoadedCellCacheLoader<DoubleType, VolatileDoubleArray> cacheLoader = LoadedCellCacheLoader.get(grid, loader, new DoubleType(), PrimitiveType.DOUBLE, AccessFlags.setOf(AccessFlags.VOLATILE));
                        return new CachedCellImg<>(grid, new DoubleType(), cache.withLoader(cacheLoader), new VolatileDoubleArray(1, true));
                    })
                    .toArray(RandomAccessibleInterval[]::new);
            final RandomAccessibleInterval<VolatileDoubleType>[] vdata = Stream
                    .of(data)
                    .map(VolatileViews::wrapAsVolatile)
                    .toArray(RandomAccessibleInterval[]::new);

            final InvalidateAll invalidateAll = () -> {
                Stream.of(data).forEach(rai -> ((CachedCellImg<?, ?>)rai).getCache().invalidateAll());
            };

            this.distanceTransform = new RandomAccessibleIntervalDataSource<>(
                    new ValueTriple<>(data, vdata, transforms),
                    invalidateAll,
                    interpolation -> Interpolation.NLINEAR.equals(interpolation) ? new NLinearInterpolatorFactory<>() : new NearestNeighborInterpolatorFactory<>(),
                    interpolation -> Interpolation.NLINEAR.equals(interpolation) ? new NLinearInterpolatorFactory<>() : new NearestNeighborInterpolatorFactory<>(),
                    getName());

        }

        @Override
        public RandomAccessibleInterval<DoubleType> getDataSource(int t, int level) {
            return distanceTransform.getDataSource(t, level);
        }

        @Override
        public RealRandomAccessible<DoubleType> getInterpolatedDataSource(int t, int level, Interpolation interpolation) {
            return distanceTransform.getInterpolatedDataSource(t, level, interpolation);
        }

        @Override
        public DoubleType getDataType() {
            return new DoubleType();
        }

        @Override
        public boolean isPresent(int t) {
            return true;
        }

        @Override
        public RandomAccessibleInterval<VolatileDoubleType> getSource(int t, int level) {
            return distanceTransform.getSource(t, level);
        }

        @Override
        public RealRandomAccessible<VolatileDoubleType> getInterpolatedSource(int t, int level, Interpolation method) {
            return distanceTransform.getInterpolatedSource(t, level, method);
        }

        @Override
        public void getSourceTransform(int t, int level, AffineTransform3D transform) {
            distanceTransform.getSourceTransform(t, level, transform);
        }

        @Override
        public VolatileDoubleType getType() {
            return new VolatileDoubleType();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public VoxelDimensions getVoxelDimensions() {
            return null;
        }

        @Override
        public int getNumMipmapLevels() {
            return sampledFunction.getNumMipmapLevels();
        }

        @Override
        public void invalidateAll() {
            distanceTransform.invalidateAll();
        }
    }

    private final ObjectProperty<int[]> halo = new SimpleObjectProperty<>(new int[] {0, 0, 0});

    private final ObjectProperty<int[]> blockSize = new SimpleObjectProperty<>(new int[] {16, 16, 16});

    private final ObjectProperty<double[]> weights = new SimpleObjectProperty<>(new double[] {1.0, 1.0, 1.0});

    private final ObjectProperty<DistanceTransform.DISTANCE_TYPE> dtType = new SimpleObjectProperty<>(DistanceTransform.DISTANCE_TYPE.EUCLIDIAN);

    private final DoubleProperty scaleFactor = new SimpleDoubleProperty(1.0);

    private final ObjectProperty<Predicate<DoubleType>> threshold = new SimpleObjectProperty<>(null);

    private final DoubleProperty sampleExtension = new SimpleDoubleProperty(1000.0);

    private final BooleanProperty squareInput = new SimpleBooleanProperty(false);

    private final List<Runnable> onUpdateListeners = new ArrayList<>();


    public DistanceTransformSourceState(
            SourceState<? extends RealType<?>, ?> dataSource,
            String name) {
        super(
                new DistanceTransformSource(dataSource.getDataSource(), name),
                new ARGBColorConverter.InvertingImp1<>(),
                new ARGBCompositeAlphaAdd(),
                name,
                dataSource);
        halo.addListener(obs -> updateDistanceTransformLoaderFactory());
        weights.addListener(obs -> updateDistanceTransformLoaderFactory());
        dtType.addListener(obs -> updateDistanceTransformLoaderFactory());
        scaleFactor.addListener(obs -> updateDistanceTransformLoaderFactory());
        threshold.addListener(obs -> updateDistanceTransformLoaderFactory());
        sampleExtension.addListener(obs -> updateDistanceTransformLoaderFactory());
        squareInput.addListener(obs -> updateDistanceTransformLoaderFactory());
        blockSize.addListener((obs, oldv, newv) -> getDataSource().setBlockSize(newv));
        blockSize.addListener((obs, oldv, newv) -> onUpdate());
    }

    private void onUpdate() {
        onUpdateListeners.forEach(Runnable::run);
    }

    private void updateDistanceTransformLoaderFactory() {
        Function<DataSource<? extends RealType<?>, ?>, IntFunction<CellLoader<DoubleType>>> factory = sampledFunction -> level -> img -> {
            final int[] halo = this.halo.get();
            final FinalInterval withContext = Intervals.expand(img, new FinalDimensions(halo));
            final boolean squareInput = this.squareInput.get();
            final RandomAccessibleInterval<DoubleType> convertedSampledFunction =
                    squareInput
                            ? Converters.convert(sampledFunction.getDataSource(0, level), new RealDoubleConverter(), new DoubleType())
                            : Converters.convert(sampledFunction.getDataSource(0, level), (s, t) -> t.setReal(s.getRealDouble() * s.getRealDouble()), new DoubleType());
            final IntervalView<DoubleType> sampled = Views.interval(Views.extendValue(convertedSampledFunction, new DoubleType(sampleExtension.get())), withContext);
            final RandomAccessibleInterval<DoubleType> dt = ArrayImgs.doubles(Intervals.dimensionsAsLongArray(withContext));
            final DistanceTransform.DISTANCE_TYPE distanceType = dtType.get();
            final Predicate<DoubleType> threshold = this.threshold.get();
            if (threshold == null) {
                DistanceTransform.transform(
                        Views.zeroMin(sampled),
                        dt,
                        distanceType,
                        DoubleStream.of(weights.get()).map(d -> d * scaleFactor.get()).toArray());
            } else {
                DistanceTransform.binaryTransform(
                        Converters.convert((RandomAccessibleInterval<DoubleType>)Views.zeroMin(sampled), (s, t) -> t.set(threshold.test(s)), new BitType()),
                        dt,
                        distanceType,
                        weights.get());
            }
            if (DistanceTransform.DISTANCE_TYPE.EUCLIDIAN.equals(distanceType)) {
                Views.interval(Views.translate(dt, Intervals.minAsLongArray(withContext)), img).forEach(v -> v.setReal(Math.sqrt(v.getRealDouble())));
            }
            LoopBuilder
                    .setImages(img, Views.interval(Views.translate(dt, Intervals.minAsLongArray(withContext)), img))
                    .forEachPixel(DoubleType::set);
        };
        getDataSource().setLoaderFactory(factory);
        onUpdate();

    }

    @Plugin(type = OpenDialogMenuEntry.class,
            menuPath = "_Features>_Distance Transform")
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
                alert.setHeaderText("Distance transform on");
                final ObservableList<SourceState<? extends RealType<?>, ?>> observableSources = FXCollections.observableArrayList(sources);
                final ComboBox<SourceState<? extends RealType<?>, ?>> comboBox = new ComboBox<>(observableSources);
                alert.getDialogPane().setContent(comboBox);
                final Optional<ButtonType> bt = alert.showAndWait();
                if (bt.filter(ButtonType.OK::equals).isPresent() && comboBox.getValue() != null) {
                    final SourceState<? extends RealType<?>, ?> raw = comboBox.getValue();
                    final DistanceTransformSourceState distanceTransform = new DistanceTransformSourceState(
                            raw,
                            raw.nameProperty().get() + "-distance-transform");
                    pbv.addState(distanceTransform);
                }
            };
        }
    }

    @Override
    public void onAdd(PainteraBaseView paintera) {
        InvalidationListener requestRepaint = obs -> paintera.orthogonalViews().requestRepaint();
        converter().minProperty().addListener(requestRepaint);
        converter().maxProperty().addListener(requestRepaint);
        converter().colorProperty().addListener(requestRepaint);
        converter().alphaProperty().addListener(requestRepaint);
        onUpdateListeners.add(paintera.orthogonalViews()::requestRepaint);
    }

    private static class Settings implements BindUnbindAndNodeSupplier {

        private static class ThresholdPredicate implements Predicate<DoubleType> {
            private final double t;

            private ThresholdPredicate(double t) {
                this.t = t;
            }

            @Override
            public boolean test(DoubleType doubleType) {
                return doubleType.getRealDouble() > t;
            }
        }

        private final DistanceTransformSourceState state;

        private final ObjectProperty<int[]> halo = new SimpleObjectProperty<>(new int[] {0, 0, 0});

        private final ObjectProperty<int[]> blockSize = new SimpleObjectProperty<>(new int[] {16, 16, 16});

        private final ObjectProperty<double[]> weights = new SimpleObjectProperty<>(new double[] {1.0, 1.0, 1.0});

        private final ObjectProperty<DistanceTransform.DISTANCE_TYPE> dtType = new SimpleObjectProperty<>(DistanceTransform.DISTANCE_TYPE.EUCLIDIAN);

        private final DoubleProperty scaleFactor = new SimpleDoubleProperty(1.0);

        private final ObjectProperty<Predicate<DoubleType>> threshold = new SimpleObjectProperty<>(null);

        private final DoubleProperty sampleExtension = new SimpleDoubleProperty(1000.0);

        private final BooleanProperty squareInput = new SimpleBooleanProperty(false);

        private final DoubleProperty thresholdAt = new SimpleDoubleProperty(0.0);

        private final BooleanProperty doThreshold = new SimpleBooleanProperty(false);

        private final ObjectBinding<Predicate<DoubleType>> thresholdBinding = Bindings.createObjectBinding(
                () -> doThreshold.get() ? new ThresholdPredicate(thresholdAt.get())  : null,
                thresholdAt,
                doThreshold);

        private Settings(DistanceTransformSourceState state) {
            this.state = state;
            this.thresholdBinding.addListener((obs, oldv, newv) -> this.threshold.set(newv));
            this.threshold.addListener((obs, oldv, newv) -> this.doThreshold.set(newv != null));
        }

        @Override
        public Node get() {

            final GridPane gp = new GridPane();
            final Label distanceType = Labels.withTooltip("Distance Type");
            final Label weights = Labels.withTooltip("Weights");
            final Label scaleFactor = Labels.withTooltip("Scale Factor");
            final Label threshold = Labels.withTooltip("Threshold");
            final Label extension = Labels.withTooltip("Extension");
            final Label squareInput = Labels.withTooltip("Square Input");
            final Label halo = Labels.withTooltip("Halo", "Padding around blocks for calculating distance transform");
            final Label blockSize = Labels.withTooltip("Block Size");

            final ComboBox<DistanceTransform.DISTANCE_TYPE> dtChoice = new ComboBox<>(FXCollections.observableArrayList(DistanceTransform.DISTANCE_TYPE.values()));
            dtChoice.valueProperty().bindBidirectional(this.dtType);
            dtChoice.setValue(this.dtType.get());

            final NumberField<DoubleProperty> weightsX = NumberField.doubleField(this.weights.get()[0], i -> i > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            final NumberField<DoubleProperty> weightsY = NumberField.doubleField(this.weights.get()[1], i -> i > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            final NumberField<DoubleProperty> weightsZ = NumberField.doubleField(this.weights.get()[2], i -> i > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            weightsX.valueProperty().addListener(obs -> this.weights.set(new double[] {weightsX.valueProperty().get(), weightsY.valueProperty().get(), weightsZ.valueProperty().get()}));
            weightsY.valueProperty().addListener(obs -> this.weights.set(new double[] {weightsX.valueProperty().get(), weightsY.valueProperty().get(), weightsZ.valueProperty().get()}));
            weightsZ.valueProperty().addListener(obs -> this.weights.set(new double[] {weightsX.valueProperty().get(), weightsY.valueProperty().get(), weightsZ.valueProperty().get()}));

            final NumberField<DoubleProperty> scaleFactorField = NumberField.doubleField(this.scaleFactor.get(), i -> i > 0, ObjectField.SubmitOn.values());
            bind(scaleFactorField.valueProperty(), this.scaleFactor);

            final CheckBox thresholdBox = new CheckBox();
            bind(thresholdBox.selectedProperty(), this.doThreshold);
            final NumberField<DoubleProperty> thresholdField = NumberField.doubleField(this.thresholdAt.get(), i -> true, ObjectField.SubmitOn.values());
            bind(thresholdField.valueProperty(), this.thresholdAt);

            final NumberField<DoubleProperty> extensionField = NumberField.doubleField(this.sampleExtension.get(), i -> i > 0, ObjectField.SubmitOn.values());
            bind(extensionField.valueProperty(), this.sampleExtension);

            final CheckBox squareBox = new CheckBox();
            bind(squareBox.selectedProperty(), this.squareInput);

            final NumberField<IntegerProperty> haloX = NumberField.intField(this.halo.get()[0], i -> i >= 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            final NumberField<IntegerProperty> haloY = NumberField.intField(this.halo.get()[1], i -> i >= 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            final NumberField<IntegerProperty> haloZ = NumberField.intField(this.halo.get()[2], i -> i >= 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            haloX.valueProperty().addListener(obs -> this.halo.set(new int[] {haloX.valueProperty().get(), haloY.valueProperty().get(), haloZ.valueProperty().get()}));
            haloY.valueProperty().addListener(obs -> this.halo.set(new int[] {haloX.valueProperty().get(), haloY.valueProperty().get(), haloZ.valueProperty().get()}));
            haloZ.valueProperty().addListener(obs -> this.halo.set(new int[] {haloX.valueProperty().get(), haloY.valueProperty().get(), haloZ.valueProperty().get()}));

            final NumberField<IntegerProperty> blockSizeX = NumberField.intField(this.blockSize.get()[0], i -> i > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            final NumberField<IntegerProperty> blockSizeY = NumberField.intField(this.blockSize.get()[1], i -> i > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            final NumberField<IntegerProperty> blockSizeZ = NumberField.intField(this.blockSize.get()[2], i -> i > 0, ObjectField.SubmitOn.ENTER_PRESSED, ObjectField.SubmitOn.FOCUS_LOST);
            blockSizeX.valueProperty().addListener((obs -> this.blockSize.set(new int[] {blockSizeX.valueProperty().get(), blockSizeY.valueProperty().get(), blockSizeZ.valueProperty().get()})));
            blockSizeY.valueProperty().addListener((obs -> this.blockSize.set(new int[] {blockSizeX.valueProperty().get(), blockSizeY.valueProperty().get(), blockSizeZ.valueProperty().get()})));
            blockSizeZ.valueProperty().addListener((obs -> this.blockSize.set(new int[] {blockSizeX.valueProperty().get(), blockSizeY.valueProperty().get(), blockSizeZ.valueProperty().get()})));


            gp.add(distanceType, 0, 0);
            gp.add(dtChoice, 3, 0);

            gp.add(weights, 0, 1);
            gp.add(weightsX.textField(), 1, 1);
            gp.add(weightsY.textField(), 2, 1);
            gp.add(weightsZ.textField(), 3, 1);

            gp.add(scaleFactor, 0, 2);
            gp.add(scaleFactorField.textField(), 3, 2);

            gp.add(threshold, 0, 3);
            gp.add(thresholdBox, 1, 3);
            gp.add(thresholdField.textField(), 3, 3);

            gp.add(extension, 0, 4);
            gp.add(extensionField.textField(), 3, 4);

            gp.add(squareInput, 0, 5);
            gp.add(squareBox, 3, 5);

            gp.add(halo, 0, 6);
            gp.add(haloX.textField(), 1, 6);
            gp.add(haloY.textField(), 2, 6);
            gp.add(haloZ.textField(), 3, 6);

            gp.add(blockSize, 0, 7);
            gp.add(blockSizeX.textField(), 1, 7);
            gp.add(blockSizeY.textField(), 2, 7);
            gp.add(blockSizeZ.textField(), 3, 7);

            return TitledPanes.createCollapsed("Settings", gp);
        }

        @Override
        public void bind() {
            bind(this.halo, state.halo);
            bind(this.blockSize, state.blockSize);
            bind(this.weights, state.weights);
            bind(this.dtType, state.dtType);
            bind(this.scaleFactor, state.scaleFactor);
            bind(this.threshold, state.threshold);
            bind(this.sampleExtension, state.sampleExtension);
            bind(this.squareInput, state.squareInput);
        }

        @Override
        public void unbind() {
            this.halo.unbindBidirectional(state.halo);
            this.blockSize.unbindBidirectional(state.blockSize);
            this.weights.unbindBidirectional(state.weights);
            this.dtType.unbindBidirectional(state.dtType);
            this.scaleFactor.unbindBidirectional(state.scaleFactor);
            this.threshold.unbindBidirectional(state.threshold);
            this.sampleExtension.unbindBidirectional(state.sampleExtension);
            this.squareInput.unbindBidirectional(state.squareInput);
        }

        private <T> void bind(ObjectProperty<T> property, ObjectProperty<T> to) {
            property.bindBidirectional(to);
            property.set(to.get());
        }

        private void bind(DoubleProperty property, DoubleProperty to) {
            property.bindBidirectional(to);
            property.set(to.get());
        }

        private void bind(BooleanProperty property, BooleanProperty to) {
            property.bindBidirectional(to);
            property.set(to.get());
        }
    }




    @Plugin(type = SourceStateUIElementsDefaultFactory.AdditionalBindUnbindSuppliersFactory.class)
    public static class LabelSourceStateAdditionalBindAndUnbindSupplierFactory implements SourceStateUIElementsDefaultFactory.AdditionalBindUnbindSuppliersFactory<DistanceTransformSourceState>
    {

        @Override
        public BindUnbindAndNodeSupplier[] create(DistanceTransformSourceState state) {
            return new BindUnbindAndNodeSupplier[] {
                new Settings(state)
            };
        }

        @Override
        public Class<DistanceTransformSourceState> getTargetClass() {
            return DistanceTransformSourceState.class;
        }
    }

}
