package com.hippo.ehviewer.library

/** A connection pauses this session; acknowledgement dismisses only its notice. */
internal class OtgLiveUpdatePause {
    private var mountedIds = emptySet<String>()
    var pausedIds = emptySet<String>()
        private set
    var noticeIds = emptySet<String>()
        private set

    fun start(volumes: List<UsbVolume>) {
        mountedIds = otgIds(volumes)
        resume()
    }

    fun update(volumes: List<UsbVolume>) {
        val ids = otgIds(volumes)
        val added = ids - mountedIds
        mountedIds = ids
        pausedIds = pausedIds.intersect(ids) + added
        noticeIds = noticeIds.intersect(ids) + added
    }

    fun acknowledge() {
        noticeIds = emptySet()
    }

    fun resume() {
        pausedIds = emptySet()
        noticeIds = emptySet()
    }

    private fun otgIds(volumes: List<UsbVolume>): Set<String> = volumes
        .filter { it.kind == ExternalStorageKind.UsbDrive }
        .map { it.uuid.lowercase() }.toSet()
}
