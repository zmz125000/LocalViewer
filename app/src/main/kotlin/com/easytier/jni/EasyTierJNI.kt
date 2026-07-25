package com.easytier.jni

/**
 * EasyTier JNI bridge. Package and method names must stay stable — the prebuilt
 * native library exports `Java_com_easytier_jni_EasyTierJNI_*` symbols.
 */
object EasyTierJNI {

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var loadError: String? = null

    /**
     * Load native libraries once. Safe to call from any thread; failures are recorded
     * in [loadError] and return false without throwing.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        loadError?.let { return false }
        return try {
            // libeasytier_android_jni.so NEEDs libeasytier_ffi.so; the linker resolves
            // the dependency from the same jniLibs ABI directory when the JNI lib loads.
            System.loadLibrary("easytier_android_jni")
            libraryLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: e.toString()
            false
        } catch (e: SecurityException) {
            loadError = e.message ?: e.toString()
            false
        }
    }

    fun isLibraryLoaded(): Boolean = libraryLoaded

    fun libraryLoadError(): String? = loadError

    external fun setTunFd(instanceName: String, fd: Int): Int

    external fun parseConfig(config: String): Int

    external fun runNetworkInstance(config: String): Int

    /**
     * Keep only the named instances; null or empty stops all.
     */
    external fun retainNetworkInstance(instanceNames: Array<String>?): Int

    external fun collectNetworkInfos(): String

    external fun getLastError(): String?

    fun stopAllInstances(): Int = retainNetworkInstance(null)
}
