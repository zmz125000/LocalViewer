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
        // Reuse a real 10-bit codestream with SDR color metadata to exercise the
        // software fallback on devices without platform P010. No codec pixels
        // are changed; only the declared transfer function is changed to sRGB.
        byte[] sdrBytes = bytes.clone();
        int changed = 0;
        for (int i = 0; i + 11 <= sdrBytes.length; ++i) {
            if (sdrBytes[i] == 'n' && sdrBytes[i+1] == 'c' && sdrBytes[i+2] == 'l' && sdrBytes[i+3] == 'x' &&
                sdrBytes[i+6] == 0 && sdrBytes[i+7] == 16) {
                sdrBytes[i+7] = 13;
                ++changed;
            }
        }
        check(changed > 0, "SDR fixture needs a nclx transfer");
        var sdr = HevcHdrDecode.INSTANCE.decode(sdrBytes, 768, true);
        check(sdr != null && !sdr.isHdr() && sdr.getTransfer() == 13, "SDR fallback was marked HDR");
        check(sdr.getGamut() == 2 && sdr.getBoost() == 1f, "SDR wide-gamut metadata lost");
        check(sdr.getPixels().length == sdr.getWidth() * sdr.getHeight() * 8, "SDR fallback lost F16 precision");
        System.out.println("SDR 10-bit HEIF fallback preserved F16 precision and BT.2020 without forcing HDR");
    }
}
