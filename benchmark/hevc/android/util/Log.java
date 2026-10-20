package android.util;
// Host integration benchmark only. Android uses the platform logger.
public final class Log {
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { throw new AssertionError(message, error); }
}
