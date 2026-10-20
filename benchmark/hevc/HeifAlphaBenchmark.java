// Host JNI integration check. Same classpath/stubs as HevcHdrBenchmark.
import com.hippo.ehviewer.image.hdr.HeifContainer;
import com.hippo.ehviewer.image.hdr.HevcHdrDecode;
import com.hippo.ehviewer.jni.HdrConvertKt;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HeifAlphaBenchmark {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static int half(byte[] bytes, int at) {
        return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8);
    }
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        byte[] bytes = Files.readAllBytes(Path.of(args[1]));
        var parsed = HeifContainer.INSTANCE.parse(bytes);
        var alpha = parsed.alphaImage();
        check(alpha != null, "auxiliary alpha missing");
        var unit = parsed.primaryImage().getTiles().get(0);
        int[] info = new int[7];
        check(HdrConvertKt.decodeHevcAnnexB(unit.getCsd(), unit.getPicture(), info) != null, "color decode failed");
        check(info[3] * 2 == info[0] && info[4] == info[1], "expected HEVC 4:2:2");
        unit = alpha.getTiles().get(0);
        short[] coverage = HdrConvertKt.decodeHevcAnnexB(unit.getCsd(), unit.getPicture(), new int[7]);
        check(coverage != null, "alpha decode failed");
        for (int edge : new int[]{0, 300}) {
            var frame = HevcHdrDecode.INSTANCE.decode(bytes, edge, true);
            check(frame != null && !frame.isHdr(), "SDR frame decode failed");
            check(frame.getGamut() == 0, "ICC destination must be linear scRGB");
            byte[] pixels = frame.getPixels();
            int transparent = 0, opaque = 0, fractional = 0;
            int[] expected = new int[pixels.length / 8];
            // The color/alpha assembly chooses the final source sample mapped to
            // each destination pixel, including the reduced-size preview path.
            for (int y = 0; y < alpha.getHeight(); y++) {
                int oy = (int)((long)y * frame.getHeight() / alpha.getHeight());
                for (int x = 0; x < alpha.getWidth(); x++) {
                    int ox = (int)((long)x * frame.getWidth() / alpha.getWidth());
                    expected[oy * frame.getWidth() + ox] = coverage[y * alpha.getWidth() + x] & 65535;
                }
            }
            for (int i = 0; i < expected.length; i++) {
                int a = half(pixels, 8 * i + 6);
                check(Math.abs(Float.float16ToFloat((short)a) - expected[i] / 255f) < 0.0005f, "alpha coverage changed");
                if (expected[i] == 0) {
                    check(a == 0, "transparent alpha lost");
                    for (int c = 0; c < 3; c++) check((half(pixels, 8 * i + c * 2) & 32767) == 0, "transparent RGB is not premultiplied");
                    transparent++;
                } else if (expected[i] == 255) {
                    check(a == 0x3c00, "opaque alpha lost");
                    opaque++;
                } else {
                    check(a > 0 && a < 0x3c00, "fractional alpha lost");
                    fractional++;
                }
            }
            check(transparent > 0 && opaque > 0 && fractional > 0, "alpha coverage test is incomplete");
            System.out.printf("SDR HEVC 4:2:2 + ICC + alpha: %dx%d, transparent=%d opaque=%d fractional=%d%n",
                frame.getWidth(), frame.getHeight(), transparent, opaque, fractional);
        }
    }
}
