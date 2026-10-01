package io.earthmover.icechunk.examples;

import dev.zarr.zarrjava.v3.Array;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.S3Credentials;
import io.earthmover.icechunk.S3Options;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Version;
import io.earthmover.icechunk.zarr.IcechunkZarrStore;

/**
 * Read one time step of ERA5 2 m temperature from the public WeatherBench2 repository on S3, with no credentials.
 *
 * <p>The repository is listed on the icechunk sample datasets page.
 */
public final class ReadPublicData {
    public static void main(String[] args) throws Exception {
        S3Options options = S3Options.builder("icechunk-public-data")
                .prefix("v1/era5_weatherbench2")
                .region("us-east-1")
                .credentials(S3Credentials.anonymous())
                .build();

        try (Storage storage = Storage.s3(options);
                Repository repo = Repository.open(storage);
                Session session = repo.readonlySession(Version.branch("main"))) {
            System.out.println("branches: " + repo.listBranches());
            System.out.println("groups:   " + session.store().listDir(""));

            IcechunkZarrStore store = new IcechunkZarrStore(session);
            Array temperature = Array.open(store.resolve("1x721x1440", "2m_temperature"));
            long[] shape = temperature.metadata().shape;
            System.out.printf(
                    "2m_temperature: %d times x %d latitudes x %d longitudes%n", shape[0], shape[1], shape[2]);

            float[] latitude = (float[])
                    Array.open(store.resolve("1x721x1440", "latitude")).read().get1DJavaArray(ucar.ma2.DataType.FLOAT);
            float[] firstStep = (float[]) temperature
                    .read(new long[] {0, 0, 0}, new long[] {1, shape[1], shape[2]})
                    .get1DJavaArray(ucar.ma2.DataType.FLOAT);

            int equator = nearest(latitude, 0f);
            double sum = 0;
            for (int lon = 0; lon < shape[2]; lon++) {
                sum += firstStep[(int) (equator * shape[2] + lon)];
            }
            System.out.printf(
                    "mean 2 m temperature along latitude %.2f at the first time step: %.1f K%n",
                    latitude[equator], sum / shape[2]);
        }
    }

    private static int nearest(float[] values, float target) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - target) < Math.abs(values[best] - target)) {
                best = i;
            }
        }
        return best;
    }
}
