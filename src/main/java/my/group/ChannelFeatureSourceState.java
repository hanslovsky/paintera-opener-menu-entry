package my.group;

import bdv.util.volatiles.VolatileViews;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.algorithm.gradient.PartialDerivative;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.DiskCachedCellImgFactory;
import net.imglib2.cache.img.DiskCachedCellImgOptions;
import net.imglib2.converter.ARGBCompositeColorConverter;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.AbstractVolatileRealType;
import net.imglib2.view.Views;
import net.imglib2.view.composite.RealComposite;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.RandomAccessibleIntervalDataSource;
import org.janelia.saalfeldlab.paintera.data.n5.VolatileWithSet;
import org.janelia.saalfeldlab.paintera.state.MinimalSourceState;
import org.janelia.saalfeldlab.paintera.state.SourceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ChannelFeatureSourceState<
        D extends RealType<D> & NativeType<D>,
        T extends AbstractVolatileRealType<D, T>,
        C extends RealComposite<D>,
        V extends Volatile<RealComposite<T>>> extends MinimalSourceState<C, V, DataSource<C, V>, ARGBCompositeColorConverter<T, RealComposite<T>, V>> {

    interface ChannelFeature<
            D extends RealType<D>,
            T extends AbstractVolatileRealType<D, T>,
            C extends RealComposite<D>,
            V extends Volatile<RealComposite<T>>> {

        List<SourceState<? extends RealType<?>, ?>> dependsOn();

        DataSource<C, V> featureSource(String name);

        RandomAccessibleInterval<D> getChannelData(int channel, int level);

        int numChannels();

    }

    public static class GradientFeature<D extends RealType<D> & NativeType<D>, T extends AbstractVolatileRealType<D, T>>
            implements ChannelFeature<D, T, RealComposite<D>, VolatileWithSet<RealComposite<T>>> {

        private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

        private final D d;

        private final SourceState<? extends RealType<?>, ?> dependsOn;

        private final int nDim;

        private final List<RandomAccessibleInterval<D>[]> data;

        private final List<RandomAccessibleInterval<T>[]> vdata;

        private final RandomAccessibleInterval<RealComposite<D>>[] collapsedData;

        private final RandomAccessibleInterval<VolatileWithSet<RealComposite<T>>>[] vcollapsedData;

        private final Converter<RealComposite<T>, VolatileWithSet<RealComposite<T>>> viewerConverter = (source, target ) -> {
            target.setT(source);
            boolean isValid = true;
            int numChannels = (int) this.numChannels();
            // TODO exchange this with only check for first index if block size == num channels
            for (int i = 0; i < numChannels && isValid; ++i)
                isValid &= source.get(i).isValid();
            target.setValid(isValid);
        };

        public GradientFeature(
                final D d,
                final SourceState<? extends RealType<?>, ?> dependsOn,
                final String cacheDir) {
            this.dependsOn = dependsOn;
            this.d = d;
            this.nDim = dependsOn.getDataSource().getDataSource(0, 0).numDimensions();
            this.data = makeGradients(dependsOn, cacheDir);
            this.vdata = new ArrayList<>();
            for (RandomAccessibleInterval<D>[] dat : this.data)
                this.vdata.add(Stream.of(dat).map(VolatileViews::wrapAsVolatile).toArray(RandomAccessibleInterval[]::new));
            collapsedData = new RandomAccessibleInterval[dependsOn.getDataSource().getNumMipmapLevels()];
            vcollapsedData = new RandomAccessibleInterval[dependsOn.getDataSource().getNumMipmapLevels()];
            for (int level = 0; level < collapsedData.length; ++level) {
                final int flevel = level;
                collapsedData[level] = Views.collapseReal(Views.stack(data.stream().map(dat -> dat[flevel]).collect(Collectors.toList())));

                final Converter<RealComposite<T>, VolatileWithSet<RealComposite<T>>> viewerConverter = (source, target ) -> {
                    target.setT(source);
                    boolean isValid = true;
                    int numChannels = this.data.size();
                    // TODO exchange this with only check for first index if block size == num channels
                    for (int i = 0; i < numChannels && isValid; ++i)
                        isValid &= source.get(i).isValid();
                    target.setValid(isValid);
                };
                vcollapsedData[level] = Converters.convert(
                        Views.collapseReal(Views.stack(vdata.stream().map(dat -> dat[flevel]).collect(Collectors.toList()))),
                        viewerConverter,
                        new VolatileWithSet<>(null, true));
            }
        }

        @Override
        public List<SourceState<? extends RealType<?>, ?>> dependsOn() {
            return Collections.singletonList(dependsOn);
        }

        @Override
        public DataSource<RealComposite<D>, VolatileWithSet<RealComposite<T>>> featureSource(final String name) {
            final DataSource<? extends RealType<?>, ?> dataSource = this.dependsOn.getDataSource();
            final AffineTransform3D[] transforms = IntStream
                    .range(0, dataSource.getNumMipmapLevels())
                    .mapToObj(lvl -> {final AffineTransform3D tf = new AffineTransform3D(); dataSource.getSourceTransform(0, lvl, tf); return tf;})
                    .toArray(AffineTransform3D[]::new);
            return new RandomAccessibleIntervalDataSource<>(
                    collapsedData,
                    vcollapsedData,
                    transforms,
                    () -> {},
                    interpolation -> new NearestNeighborInterpolatorFactory<>(),
                    interpolation -> new NearestNeighborInterpolatorFactory<>(),
                    name);
        }

        @Override
        public RandomAccessibleInterval<D> getChannelData(int channel, int level) {
            return data.get(channel)[level];
        }

        @Override
        public int numChannels() {
            return data.size();
        }

        private List<RandomAccessibleInterval<D>[]> makeGradients(SourceState<? extends RealType<?>, ?> dependsOn, final String cacheDir) {
            final DataSource<? extends RealType<?>, ?> dataSource = dependsOn.getDataSource();
            final int numLevels = dataSource.getNumMipmapLevels();
            final AffineTransform3D[] tfs = IntStream
                    .range(0, numLevels)
                    .mapToObj(lvl -> { AffineTransform3D tf = new AffineTransform3D(); dataSource.getSourceTransform(0, lvl, tf); return tf;})
                    .toArray(AffineTransform3D[]::new);

            List<RandomAccessibleInterval<D>[]> data = new ArrayList<>();

            final DiskCachedCellImgOptions options = DiskCachedCellImgOptions
                    .options()
                    .tempDirectory(Paths.get(cacheDir))
                    .tempDirectoryPrefix("gradient-")
                    .deleteCacheDirectoryOnExit(true)
                    .cellDimensions(32, 32, 32)
                    .volatileAccesses(true);

            final int nDim = dataSource.getDataSource(0, 0).numDimensions();
            IntStream.range(0, nDim).forEach(d -> data.add(new RandomAccessibleInterval[numLevels]));


            for (int lvl = 0; lvl < numLevels; ++lvl) {
                final RandomAccessibleInterval<D> raw = Converters.convert(
                        dataSource.getDataSource(0, lvl),
                        (src, tgt) -> tgt.setReal(src.getRealDouble()),
                        d.createVariable());
                final RandomAccessible<D> rawExtended = Views.extendBorder(raw);
                final DiskCachedCellImgFactory<D> factory = new DiskCachedCellImgFactory<>(d, options);

                for (int dim = 0; dim < nDim; ++dim) {
                    final int fdim = dim;
                    CellLoader<D> loader = img -> PartialDerivative.gradientCentralDifference(rawExtended, img, fdim);
                    data.get(dim)[lvl] = factory.create(raw, loader, options);
                }

            }
            return data;
        }

    }


    private final ChannelFeature<D, T, C, V> feature;

    public ChannelFeatureSourceState(
            final ChannelFeature<D, T, C, V> feature,
            final String name,
            final String cacheDir) {
        super(
                feature.featureSource(name),
                new ARGBCompositeColorConverter.InvertingImp1<T, RealComposite<T>, V>(feature.numChannels()),
                new ARGBCompositeAlphaAdd(),
                name,
                feature.dependsOn().stream().toArray(SourceState[]::new));
        this.feature = feature;
    }

    @Override
    public void onAdd(PainteraBaseView paintera) {
        for (int channel = 0; channel < feature.numChannels(); ++channel) {
            converter().minProperty(channel).addListener(obs -> paintera.orthogonalViews().requestRepaint());
            converter().maxProperty(channel).addListener(obs -> paintera.orthogonalViews().requestRepaint());
            converter().colorProperty(channel).addListener(obs -> paintera.orthogonalViews().requestRepaint());
        }
        converter().alphaProperty().addListener(obs -> paintera.orthogonalViews().requestRepaint());
    }



}
