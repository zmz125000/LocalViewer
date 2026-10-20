// Host integration check of the production Apple HEIC gain-map path.
// Use the same classpath as HevcHdrBenchmark; pass the JNI library and samples directory.
import com.hippo.ehviewer.image.hdr.AppleGainMapDecode;
import com.hippo.ehviewer.image.hdr.HeifContainer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppleHeicBenchmark {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        String[] names = { "hdr-sample.heic", "classic-car.heic", "greyhounds-looking-for-a-table.heic", "old-safe-wall.heic" };
        float[] expected = { 7.37f, 5.98f, 3.48f, 3.35f };
        for (int i = 0; i < names.length; ++i) {
            byte[] bytes = Files.readAllBytes(Path.of(args[1], names[i]));
            var parsed = HeifContainer.INSTANCE.parse(bytes);
            check(parsed != null, "HEIF parse failed");
            var gain = parsed.appleGainMap();
            check(gain != null && Math.abs(gain.getHeadroom() - expected[i]) < 0.08f, "Apple HDR metadata lost");
            long start = System.nanoTime();
            // Calls HevcDecoder with the production software preference. Android's
            // MediaCodec is a throwing host stub: any dependency on it fails here.
            var frame = AppleGainMapDecode.INSTANCE.decode(gain);
            check(frame != null, "Apple gain-map decode failed");
            boolean swapped = gain.getRotationDegreesCcw() % 180 != 0;
            check(frame.getWidth() == (swapped ? gain.getHeight() : gain.getWidth()), "rotated map width differs");
            check(frame.getHeight() == (swapped ? gain.getWidth() : gain.getHeight()), "rotated map height differs");
            check(frame.getPixels().length == frame.getWidth() * frame.getHeight(), "gain-map size differs");
            int max = 0;
            int min = 255;
            for (int pixel : frame.getPixels()) {
                check((pixel >>> 24) == 255, "nonopaque gain-map pixel");
                check((pixel & 255) == ((pixel >>> 8) & 255) && (pixel & 255) == ((pixel >>> 16) & 255), "gain map is not gray");
                max = Math.max(max, pixel & 255);
                min = Math.min(min, pixel & 255);
            }
            check(max > min && max > 64, "HDR highlight gain lost");
            double peakBoost = Math.pow(gain.getHeadroom(), max / 255.0);
            check(peakBoost > 1.5, "gain map cannot recover HDR highlights");
            System.out.printf("%s: %dx%d gain map, metadata %.3fx, peak gain %.3fx, %.1f ms%n",
                names[i], frame.getWidth(), frame.getHeight(), gain.getHeadroom(), peakBoost,
                (System.nanoTime() - start) / 1e6);
        }
    }
}
