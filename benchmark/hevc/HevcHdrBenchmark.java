// Host integration check for the actual Kotlin HEIF parser, JNI software decoder,
// and HDR pixel assembler. Build :app:compileDefaultDebugKotlin first. Compile
// this and android/util/Log.java with the app's Kotlin classes, kotlin-stdlib,
// and the compile-SDK android.jar on the classpath. Run with arguments:
//   /absolute/path/libhevc_hdr_test.so samples/iphonec2/DSC07633.heic
import com.hippo.ehviewer.image.hdr.HeifContainer;
import com.hippo.ehviewer.image.hdr.HevcHdrDecode;
import com.hippo.ehviewer.jni.HdrConvertKt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class HevcHdrBenchmark {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        byte[] bytes = Files.readAllBytes(Path.of(args[1]));
        var parsed = HeifContainer.INSTANCE.parse(bytes);
        check(parsed != null, "HEIF parse failed");
        var hdr = parsed.absoluteHdr();
        check(hdr != null && hdr.getTransfer() == 16, "sample must be PQ HEIF");
        long start = System.nanoTime();
        for (var tile : hdr.getTiles()) {
            int[] info = new int[5];
            short[] samples = HdrConvertKt.decodeHevcAnnexB(tile.getCsd(), tile.getPicture(), info);
            check(samples != null, "software decode failed");
            check(info[0] == tile.getWidth() && info[1] == tile.getHeight(), "decoded tile size differs");
            check(info[2] == tile.getBitDepth() && info[2] >= 10, "HDR precision lost");
            check(samples.length == info[0] * info[1] + 2 * info[3] * info[4], "plane sizes differ");
            int max = 0;
            for (short sample : samples) {
                int v = sample & 0xffff;
                check(v < (1 << info[2]), "sample is not an unshifted integer code");
                max = Math.max(max, v);
            }
            check(max > 255, "high bit depth samples lost");
        }
        System.out.printf("Decoded %d HEIF tiles at native %d-bit precision in %.1f ms%n",
            hdr.getTiles().size(), hdr.getTiles().get(0).getBitDepth(), (System.nanoTime() - start) / 1e6);
        var first = hdr.getTiles().get(0);
        short[] a = HdrConvertKt.decodeHevcAnnexB(first.getCsd(), first.getPicture(), new int[5]);
        short[] b = HdrConvertKt.decodeHevcAnnexB(first.getCsd(), first.getPicture(), new int[5]);
        check(Arrays.equals(a, b), "software decoder output is not deterministic");
        check(HdrConvertKt.decodeHevcAnnexB(new byte[0], first.getPicture(), new int[5]) == null, "empty input accepted");
        check(HdrConvertKt.decodeHevcAnnexB(first.getCsd(), first.getPicture(), new int[4]) == null, "short metadata array accepted");
        check(HdrConvertKt.decodeHevcAnnexB(first.getCsd(), new byte[]{0, 0, 1, 0}, new int[5]) == null, "invalid bitstream accepted");
        start = System.nanoTime();
        var frame = HevcHdrDecode.INSTANCE.decode(bytes, 768, true);
        check(frame != null, "HEIF HDR assembly failed");
        check(Math.max(frame.getWidth(), frame.getHeight()) == 768, "reader edge cap lost");
        check(frame.getPixels().length == frame.getWidth() * frame.getHeight() * 8, "F16 output size differs");
        check(frame.getGamut() == 2 && frame.getBoost() > 1f, "BT.2020 HDR headroom lost");
        System.out.printf("Assembled %dx%d BT.2020 F16, HDR boost=%.3f in %.1f ms%n",
            frame.getWidth(), frame.getHeight(), frame.getBoost(), (System.nanoTime() - start) / 1e6);
    }
}
