package my.group;

import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Interpolation;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.Expose;
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
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.volatiles.VolatileDoubleType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.RandomAccessibleIntervalDataSource;
import org.janelia.saalfeldlab.paintera.serialization.SerializationHelpers;
import org.janelia.saalfeldlab.paintera.serialization.StatefulSerializer;
import org.janelia.saalfeldlab.paintera.state.MinimalSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts;
import org.janelia.saalfeldlab.paintera.ui.opendialog.menu.OpenDialogMenuEntry;
import org.janelia.saalfeldlab.util.Colors;
import org.scijava.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.janelia.saalfeldlab.paintera.serialization.sourcestate.SourceStateSerialization.DEPENDS_ON_KEY;
import static org.janelia.saalfeldlab.paintera.serialization.sourcestate.SourceStateSerialization.INTERPOLATION_KEY;
import static org.janelia.saalfeldlab.paintera.serialization.sourcestate.SourceStateSerialization.IS_VISIBLE_KEY;
import static org.janelia.saalfeldlab.paintera.serialization.sourcestate.SourceStateSerialization.NAME_KEY;

public class FeatureSourceState extends MinimalSourceState<DoubleType, VolatileDoubleType, DataSource<DoubleType, VolatileDoubleType>, ARGBColorConverter<VolatileDoubleType>> {

    private interface Feature {

        DataSource<DoubleType, VolatileDoubleType> featureSource(String cacheDir, String name, SourceState<? extends RealType<?>, ?>... dependsOn);

    }

    private static class GradientFeature implements Feature {

        private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

        @Expose
        private final int dim;


        public GradientFeature(final int dim) {
            this.dim = dim;
        }

        @Override
        public DataSource<DoubleType, VolatileDoubleType> featureSource(
                final String cacheDir,
                final String name,
                final SourceState<? extends RealType<?>, ?>... dependsOn) {
            if (dependsOn.length != 1)
                throw new RuntimeException("Expected exactly one dependency but got " + dependsOn.length);
            final DataSource<? extends RealType<?>, ?> dataSource = dependsOn[0].getDataSource();
            final int numLevels = dataSource.getNumMipmapLevels();
            final AffineTransform3D[] tfs = IntStream
                    .range(0, numLevels)
                    .mapToObj(lvl -> { AffineTransform3D tf = new AffineTransform3D(); dataSource.getSourceTransform(0, lvl, tf); return tf;})
                    .toArray(AffineTransform3D[]::new);

            final RandomAccessibleInterval<DoubleType>[] data = new RandomAccessibleInterval[numLevels];
            final RandomAccessibleInterval<VolatileDoubleType>[] vdata = new RandomAccessibleInterval[numLevels];

            final DiskCachedCellImgOptions options = DiskCachedCellImgOptions
                    .options()
                    .tempDirectory(Paths.get(cacheDir))
                    .tempDirectoryPrefix("gradient-")
                    .deleteCacheDirectoryOnExit(true)
                    .cellDimensions(32, 32, 32)
                    .volatileAccesses(true);


            for (int lvl = 0; lvl < numLevels; ++lvl) {
                final RandomAccessibleInterval<DoubleType> raw = Converters.convert(
                        dataSource.getDataSource(0, lvl),
                        (src, tgt) -> tgt.setReal(src.getRealDouble()),
                        new DoubleType());
                final int nDim = raw.numDimensions();
                final RandomAccessible<DoubleType> rawExtended = Views.extendBorder(raw);
                final DiskCachedCellImgFactory<DoubleType> factory = new DiskCachedCellImgFactory<>(new DoubleType(), options);

                CellLoader<DoubleType> loader = img -> PartialDerivative.gradientCentralDifference(rawExtended, img, dim);

                data[lvl] = factory.create(raw, loader, options);
                vdata[lvl] = VolatileViews.wrapAsVolatile(data[lvl]);
            }
            return new RandomAccessibleIntervalDataSource<DoubleType, VolatileDoubleType>(
                    data,
                    vdata,
                    tfs,
                    () -> {},
                    new InterpolationFunc<>(),
                    new InterpolationFunc<>(),
                    name);
        }
    }

    private static class MagnitudeFeature implements Feature {

        private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

            @Override
            public DataSource<DoubleType, VolatileDoubleType> featureSource(final String cacheDir, final String name, final SourceState<? extends RealType<?>, ?>... dependsOn) {
                // TODO check consistency of all sources, as long as it is called only privately, do not care
                final DataSource<? extends RealType<?>, ?> dataSource = dependsOn[0].getDataSource();
                final int numLevels = dataSource.getNumMipmapLevels();
                final AffineTransform3D[] tfs = IntStream
                        .range(0, numLevels)
                        .mapToObj(lvl -> { AffineTransform3D tf = new AffineTransform3D(); dataSource.getSourceTransform(0, lvl, tf); return tf;})
                        .toArray(AffineTransform3D[]::new);

                final RandomAccessibleInterval<DoubleType>[] data = new RandomAccessibleInterval[numLevels];
                final RandomAccessibleInterval<VolatileDoubleType>[] vdata = new RandomAccessibleInterval[numLevels];

                final DiskCachedCellImgOptions options = DiskCachedCellImgOptions
                        .options()
                        .tempDirectory(Paths.get(cacheDir))
                        .tempDirectoryPrefix("gradient-")
                        .deleteCacheDirectoryOnExit(true)
                        .cellDimensions(32, 32, 32)
                        .volatileAccesses(true);


                for (int lvl = 0; lvl < numLevels; ++lvl) {
                    final RandomAccessibleInterval<DoubleType> raw = Converters.convert(
                            dataSource.getDataSource(0, lvl),
                            (src, tgt) -> tgt.setReal(src.getRealDouble()),
                            new DoubleType());
                    final DiskCachedCellImgFactory<DoubleType> factory = new DiskCachedCellImgFactory<>(new DoubleType(), options);
                    final int flvl = lvl;

                    CellLoader<DoubleType> loader = img -> {
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

    private final Feature feature;

    private FeatureSourceState(
            final Feature feature,
            final String name,
            final String cacheDir,
            SourceState<? extends RealType<?>, ?>... dependsOn) {
        super(
                feature.featureSource(cacheDir, name, dependsOn),
                new ARGBColorConverter.InvertingImp1<VolatileDoubleType>(),
                new ARGBCompositeAlphaAdd(),
                name,
                dependsOn);
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
                    final FeatureSourceState[] gradients = IntStream
                            .range(0, nDim)
                            .mapToObj(dim -> new GradientFeature(dim))
                            .map(feat -> new FeatureSourceState(feat, raw.nameProperty().getName() + "-gradient", directory, raw))
                            .toArray(FeatureSourceState[]::new);
                    final FeatureSourceState magnitude = new FeatureSourceState(
                            new MagnitudeFeature(),
                            raw.nameProperty().getName() + "-gradient-magnitude",
                            directory,
                            gradients);
                    gradients[0].converter().colorProperty().set(Colors.toARGBType("#ff0000"));
                    gradients[1].converter().colorProperty().set(Colors.toARGBType("#00ff00"));
                    gradients[2].converter().colorProperty().set(Colors.toARGBType("#0000ff"));
                    Stream.of(gradients).forEach(pbv::addState);
                    Stream.of(magnitude).forEach(pbv::addState);
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

    @Plugin(type = StatefulSerializer.SerializerAndDeserializer.class)
    public static class SerializationFactory implements StatefulSerializer.SerializerAndDeserializer<FeatureSourceState, Deserializer, Serializer> {

        @Override
        public Deserializer createDeserializer(
                StatefulSerializer.Arguments arguments,
                Supplier<String> projectDirectory,
                IntFunction<SourceState<?, ?>> dependencyFromIndex) {
            return new Deserializer(dependencyFromIndex, projectDirectory.get());
        }

        @Override
        public Serializer createSerializer(
                Supplier<String> projectDirectory,
                ToIntFunction<SourceState<?, ?>> stateToIndex) {
            return new Serializer(stateToIndex);
        }

        @Override
        public Class<FeatureSourceState> getTargetClass() {
            return FeatureSourceState.class;
        }
    }

    private static class Serializer implements JsonSerializer<FeatureSourceState> {

        private final ToIntFunction<SourceState<?, ?>> sourceToIndex;

        private Serializer(final ToIntFunction<SourceState<?, ?>> sourceToIndex) {
            this.sourceToIndex = sourceToIndex;
        }

        @Override
        public JsonElement serialize(FeatureSourceState src, Type typeOfSrc, JsonSerializationContext context) {
            final JsonObject map = new JsonObject();
            map.add("composite", SerializationHelpers.serializeWithClassInfo(src.compositeProperty().get(), context));
            map.add("converter", SerializationHelpers.serializeWithClassInfo(src.converter(), context));
            map.add("feature", SerializationHelpers.serializeWithClassInfo(src.feature, context));
            map.add(INTERPOLATION_KEY, context.serialize(src.interpolationProperty().get(), Interpolation.class));
            map.addProperty(IS_VISIBLE_KEY, src.isVisibleProperty().get());
            map.addProperty(NAME_KEY, src.nameProperty().get());
            map.add(DEPENDS_ON_KEY, context.serialize(Stream.of(src.dependsOn()).mapToInt(sourceToIndex).toArray()));
            return map;
        }
    }

    private static class Deserializer implements JsonDeserializer<FeatureSourceState> {

        private final IntFunction<SourceState<?, ?>> dependencyFromIndex;

        private final String cacheDir;

        private Deserializer(final IntFunction<SourceState<?, ?>> dependencyFromIndex, final String cacheDir) {
            this.dependencyFromIndex = dependencyFromIndex;
            this.cacheDir = cacheDir;
        }

        @Override
        public FeatureSourceState deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            final JsonObject map = json.getAsJsonObject();
            try {
                final SourceState<? extends RealType<?>, ?>[] dependsOn = IntStream
                        .of(context.deserialize(map.get(DEPENDS_ON_KEY), int[].class))
                        .mapToObj(dependencyFromIndex)
                        .toArray(SourceState[]::new);
                if (Stream.of(dependsOn).anyMatch(s -> s == null))
                    return null;
                final FeatureSourceState fs = new FeatureSourceState(
                        SerializationHelpers.deserializeFromClassInfo(map.getAsJsonObject("feature"), context),
                        map.get(NAME_KEY).getAsString(),
                        cacheDir,
                        dependsOn);
                final ARGBColorConverter<VolatileDoubleType> converter = SerializationHelpers.deserializeFromClassInfo(map.getAsJsonObject("converter"), context);
                fs.converter().setColor(converter.getColor());
                fs.converter().setMin(converter.getMin());
                fs.converter().setMax(converter.getMax());
                fs.converter().alphaProperty().set(converter.alphaProperty().get());
                fs.compositeProperty().set(SerializationHelpers.deserializeFromClassInfo(map.getAsJsonObject("composite"), context));
                fs.interpolationProperty().set(context.deserialize(map.get(INTERPOLATION_KEY), Interpolation.class));
                fs.isVisibleProperty().set(map.get(IS_VISIBLE_KEY).getAsBoolean());
                return fs;
            } catch (ClassNotFoundException e) {
                throw new JsonParseException(e);
            }
        }
    }


}
