// Open ERA5 2 m temperature from the public WeatherBench2 icechunk repository as a Fiji image stack.
//
// Needs icechunk-java and icechunk-n5 in Fiji's jars/ folder. Run it from Fiji's script editor
// (File > New > Script..., language Groovy).

import com.google.gson.GsonBuilder
import ij.IJ
import io.earthmover.icechunk.Repository
import io.earthmover.icechunk.S3Credentials
import io.earthmover.icechunk.S3Options
import io.earthmover.icechunk.Storage
import io.earthmover.icechunk.Version
import io.earthmover.icechunk.n5.IcechunkKeyValueAccess
import java.awt.GraphicsEnvironment
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader

def hours = 48

def storage = Storage.s3(S3Options.builder("icechunk-public-data")
        .prefix("v1/era5_weatherbench2")
        .region("us-east-1")
        .credentials(S3Credentials.anonymous())
        .build())
def repo = Repository.open(storage)
// Left open: the image reads chunks lazily for as long as it is shown.
def session = repo.readonlySession(Version.branch("main"))

def n5 = new ZarrV3KeyValueReader(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false)
// n5 reverses Zarr's (time, latitude, longitude) to (longitude, latitude, time): x, y and the stack.
def temperature = N5Utils.open(n5, "1x721x1440/2m_temperature")
def firstHours = Views.interval(temperature, [0, 0, 0] as long[], [1439, 720, hours - 1] as long[])

if (GraphicsEnvironment.isHeadless()) {
    def cursor = Views.flatIterable(Views.hyperSlice(firstHours, 2, 0)).cursor()
    def min = Float.MAX_VALUE, max = -Float.MAX_VALUE
    while (cursor.hasNext()) {
        def value = cursor.next().get()
        min = Math.min(min, value)
        max = Math.max(max, value)
    }
    println "dimensions: ${temperature.dimensionsAsLongArray()}"
    println "first hour: ${min} K to ${max} K"
} else {
    def image = ImageJFunctions.wrap(firstHours, "ERA5 2 m temperature (K), first ${hours} hours")
    image.setDisplayRange(200, 315)
    image.show()
    IJ.run(image, "Fire", "")
}
