-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# archive.c openArchiveStream uses GetMethodID("nativeRead"/"nativeSeek") on the
# Kotlin bridge. R8 must not rename/shrink these or release aborts with NoSuchMethodError
# (debug has minify off). Cover thumbs open the stream when browsing network archives.
-keep,allowoptimization class com.hippo.ehviewer.library.ArchiveStreamBridge {
    <init>(...);
    byte[] nativeRead(int);
    long nativeSeek(long, int);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers,allowobfuscation class com.hippo.ehviewer.coil.AnimatedWebPDrawable {
    java.nio.ByteBuffer source;
}

# Ktor logger
-dontwarn org.slf4j.impl.StaticLoggerBinder

# https://issuetracker.google.com/222232895
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.Sidecar*

# SmbAsyncTransport looks these up with getDeclaredField. R8 renaming any of
# them is ExceptionInInitializerError on the first ConnectivityManager callback
# (debug has minify off).
-keep class com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransport {
    java.nio.channels.AsynchronousSocketChannel socketChannel;
    java.util.concurrent.atomic.AtomicBoolean connected;
    com.hierynomus.smbj.transport.tcp.async.AsyncPacketReader packetReader;
    int soTimeout;
    com.hierynomus.protocol.transport.PacketHandlers handlers;
}

-keep class com.hierynomus.smbj.connection.Connection {
    com.hierynomus.smbj.connection.SequenceWindow sequenceWindow;
}

-keepattributes LineNumberTable

-allowaccessmodification
-repackageclasses
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

