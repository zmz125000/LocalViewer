package com.hippo.ehviewer.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.EnumSet

/**
 * Minimal [MS-SRVS] share enumeration over SMB `IPC$\srvsvc` (no rapid7 dcerpc).
 *
 * Wire path matches the official smbj-rpc example:
 * authenticate → tree-connect IPC$ → open `srvsvc` → DCE/RPC bind →
 * [NetrShareEnum](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-srvs)
 * (opnum 15) at information level 1.
 *
 * Only level 1 is implemented (name + type + remark) so callers can filter disk trees.
 */
internal object MsSrvsShareEnum {
    data class Share(val name: String, val type: Int)

    /** [MS-SRVS] STYPE_* low byte. */
    const val STYPE_DISKTREE = 0
    const val STYPE_TYPE_MASK = 0xFF

    private const val PIPE_NAME = "srvsvc"
    private const val OPNUM_NETR_SHARE_ENUM = 15
    private const val INFO_LEVEL_1 = 1

    /** Prefer all entries (DWORD -1). */
    private const val MAX_PREFERRED_LENGTH = -1

    private const val ERROR_SUCCESS = 0
    private const val ERROR_MORE_DATA = 0xEA

    private const val RPC_VERSION_MAJOR: Byte = 5
    private const val RPC_VERSION_MINOR: Byte = 0
    private const val PDU_REQUEST: Byte = 0
    private const val PDU_RESPONSE: Byte = 2
    private const val PDU_BIND: Byte = 11
    private const val PDU_BIND_ACK: Byte = 12
    private const val PDU_BIND_NAK: Byte = 13
    private const val PDU_FAULT: Byte = 3

    /** PFC_FIRST_FRAG | PFC_LAST_FRAG */
    private const val PFC_FIRST_LAST: Byte = 0x03
    private const val PFC_LAST: Int = 0x02

    /** Little-endian NDR ASCII IEEE float. */
    private val NDR_DREP = byteArrayOf(0x10, 0x00, 0x00, 0x00)

    // UUID wire (DCE mixed endian) + version major/minor as little-endian shorts after UUID.
    // SRVSVC 4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0
    private val SRVSVC_SYNTAX = byteArrayOf(
        0xc8.toByte(), 0x4f, 0x32, 0x4b, 0x70, 0x16, 0xd3.toByte(), 0x01,
        0x12, 0x78, 0x5a, 0x47, 0xbf.toByte(), 0x6e, 0xe1.toByte(), 0x88.toByte(),
        0x03, 0x00, // major
        0x00, 0x00, // minor
    )

    // NDR 8a885d04-1ceb-11c9-9fe8-08002b104860 v2.0
    private val NDR_SYNTAX = byteArrayOf(
        0x04, 0x5d, 0x88.toByte(), 0x8a.toByte(), 0xeb.toByte(), 0x1c, 0xc9.toByte(), 0x11,
        0x9f.toByte(), 0xe8.toByte(), 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60,
        0x02, 0x00,
        0x00, 0x00,
    )

    private const val MAX_XMIT_FRAG = 4280
    private const val MAX_RECV_FRAG = 4280

    /**
     * List all shares at level 1. Does not filter admin/`$` names — caller decides.
     */
    fun listSharesLevel1(session: Session): List<Share> {
        val tree = session.connectShare("IPC$")
        if (tree !is PipeShare) {
            tree.close()
            throw IOException("IPC$ is not a pipe share")
        }
        try {
            val pipe = openSrvsvc(tree)
            try {
                bind(pipe)
                return enumAllLevel1(pipe)
            } finally {
                runCatching { pipe.close() }
            }
        } finally {
            runCatching { tree.close() }
        }
    }

    private fun openSrvsvc(pipeShare: PipeShare): NamedPipe {
        val access = EnumSet.of(AccessMask.MAXIMUM_ALLOWED)
        val shareAccess = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE,
        )
        return pipeShare.open(
            PIPE_NAME,
            SMB2ImpersonationLevel.Impersonation,
            access,
            null,
            shareAccess,
            SMB2CreateDisposition.FILE_OPEN_IF,
            null,
        )
    }

    private fun bind(pipe: NamedPipe) {
        val body = NdrWriter().apply {
            u16(MAX_XMIT_FRAG)
            u16(MAX_RECV_FRAG)
            u32(0) // assoc_group_id
            u8(1) // n_context_elem
            u8(0)
            u8(0)
            u8(0) // reserved
            // p_cont_elem[0]
            u16(0) // p_cont_id
            u8(1) // n_transfer_syn
            u8(0) // reserved
            raw(SRVSVC_SYNTAX)
            raw(NDR_SYNTAX)
        }.toByteArray()

        val request = rpcPacket(PDU_BIND, callId = 1, afterCommonHeader = body)
        val response = pipeTransact(pipe, request)
        val ptype = response.getOrNull(2)?.toInt()?.and(0xff)
            ?: throw IOException("empty bind response")
        when (ptype) {
            PDU_BIND_ACK.toInt() and 0xff -> Unit
            PDU_BIND_NAK.toInt() and 0xff -> throw IOException("RPC bind_nak from srvsvc")
            else -> throw IOException("unexpected RPC PDU after bind: $ptype")
        }
    }

    private fun enumAllLevel1(pipe: NamedPipe): List<Share> {
        val all = ArrayList<Share>()
        var resume: Int? = 0
        var callId = 2
        var guard = 0
        while (guard++ < 64) {
            val stub = buildNetrShareEnumStub(resumeHandle = resume)
            val request = rpcRequest(callId++, OPNUM_NETR_SHARE_ENUM, stub)
            val responsePdu = pipeTransact(pipe, request)
            val stubOut = extractResponseStub(responsePdu)
            val parsed = parseNetrShareEnumLevel1(stubOut)
            all.addAll(parsed.shares)
            when (parsed.returnCode) {
                ERROR_SUCCESS -> return all
                ERROR_MORE_DATA -> {
                    val next = parsed.resumeHandle
                        ?: throw IOException("NetrShareEnum MORE_DATA without resume handle")
                    if (resume != null && next == resume) {
                        throw IOException("NetrShareEnum resume handle not advanced")
                    }
                    resume = next
                }
                else -> throw IOException(
                    "NetrShareEnum failed: 0x${parsed.returnCode.toString(16)}",
                )
            }
        }
        throw IOException("NetrShareEnum pagination exceeded limit")
    }

    /** NetrShareEnum in-stub (NDR) for level 1 with optional resume. */
    internal fun buildNetrShareEnumStub(resumeHandle: Int?): ByteArray = NdrWriter().apply {
        // ServerName: NULL unique pointer
        nullPtr()
        // SHARE_ENUM_STRUCT: Level + union switch + container pointer (empty)
        u32(INFO_LEVEL_1)
        u32(INFO_LEVEL_1) // union arm
        referent() // non-null container
        u32(0) // EntriesRead
        nullPtr() // Buffer
        u32(MAX_PREFERRED_LENGTH)
        if (resumeHandle != null) {
            referent()
            u32(resumeHandle)
        } else {
            nullPtr()
        }
    }.toByteArray()

    internal data class EnumResult(
        val shares: List<Share>,
        val returnCode: Int,
        val resumeHandle: Int?,
    )

    /**
     * Parse NetrShareEnum level-1 response stub (after RPC response header).
     * Visible for unit tests.
     */
    internal fun parseNetrShareEnumLevel1(stub: ByteArray): EnumResult {
        val r = NdrReader(stub)
        // SHARE_ENUM_STRUCT
        r.align(4)
        val level = r.u32()
        if (level != INFO_LEVEL_1) {
            throw IOException("expected share info level 1, got $level")
        }
        val unionLevel = r.u32()
        if (unionLevel != level) {
            throw IOException("share enum union level $unionLevel != $level")
        }
        val containerRef = r.u32()
        val shares = ArrayList<Share>()
        if (containerRef != 0) {
            // SHARE_INFO_1_CONTAINER
            r.align(4)
            val entriesRead = r.u32()
            val bufferRef = r.u32()
            if (bufferRef != 0 && entriesRead > 0) {
                if (entriesRead > 100_000) {
                    throw IOException("implausible EntriesRead=$entriesRead")
                }
                // Conformant array max count
                r.align(4)
                r.u32() // MaximumCount
                // Entity: n × SHARE_INFO_1 (pointer, type, pointer)
                data class Entity(var nameRef: Int, var type: Int, var remarkRef: Int)
                val entities = Array(entriesRead) {
                    r.align(4)
                    Entity(r.u32(), r.u32(), r.u32())
                }
                // Deferrals: strings in order (name then remark per entry — actually
                // all name+remark deferrals follow entity list in pointer order).
                // NDR: for each entry, name string if non-null, then for each entry remark.
                // Rapid7 unmarshals: all preambles, all entities, then all deferrals
                // where each entry's deferrals run in sequence (name then remark).
                for (e in entities) {
                    val name = if (e.nameRef != 0) r.readConformantVaryingWString() else null
                    if (e.remarkRef != 0) {
                        r.readConformantVaryingWString() // discard remark
                    }
                    if (!name.isNullOrEmpty()) {
                        shares.add(Share(name = name, type = e.type))
                    }
                }
            }
        }
        r.align(4)
        /* totalEntries = */ r.u32()
        val resumeRef = r.u32()
        val resume = if (resumeRef != 0) r.u32() else null
        r.align(4)
        val returnCode = r.u32()
        return EnumResult(shares = shares, returnCode = returnCode, resumeHandle = resume)
    }

    private fun rpcRequest(callId: Int, opNum: Int, stub: ByteArray): ByteArray {
        val afterCommon = NdrWriter().apply {
            u32(stub.size) // alloc_hint
            u16(0) // p_cont_id
            u16(opNum)
            raw(stub)
        }.toByteArray()
        return rpcPacket(PDU_REQUEST, callId, afterCommon)
    }

    private fun rpcPacket(ptype: Byte, callId: Int, afterCommonHeader: ByteArray): ByteArray {
        val fragLen = 16 + afterCommonHeader.size
        val out = ByteArrayOutputStream(fragLen)
        out.write(RPC_VERSION_MAJOR.toInt())
        out.write(RPC_VERSION_MINOR.toInt())
        out.write(ptype.toInt() and 0xff)
        out.write(PFC_FIRST_LAST.toInt() and 0xff)
        out.write(NDR_DREP)
        // frag_length LE
        out.write(fragLen and 0xff)
        out.write((fragLen ushr 8) and 0xff)
        // auth_length
        out.write(0)
        out.write(0)
        // call_id LE
        out.write(callId and 0xff)
        out.write((callId ushr 8) and 0xff)
        out.write((callId ushr 16) and 0xff)
        out.write((callId ushr 24) and 0xff)
        out.write(afterCommonHeader)
        return out.toByteArray()
    }

    private fun extractResponseStub(pdu: ByteArray): ByteArray {
        if (pdu.size < 24) throw IOException("RPC response too short (${pdu.size})")
        val ptype = pdu[2].toInt() and 0xff
        if (ptype == PDU_FAULT.toInt() and 0xff) {
            throw IOException("RPC fault from srvsvc")
        }
        if (ptype != PDU_RESPONSE.toInt() and 0xff) {
            throw IOException("expected RPC response PDU, got $ptype")
        }
        val fragLen = (pdu[8].toInt() and 0xff) or ((pdu[9].toInt() and 0xff) shl 8)
        val authLen = (pdu[10].toInt() and 0xff) or ((pdu[11].toInt() and 0xff) shl 8)
        // response header is 24 bytes before stub
        val stubLen = fragLen - authLen - 24
        if (stubLen < 0 || 24 + stubLen > pdu.size) {
            throw IOException("invalid RPC frag_length=$fragLen auth=$authLen pdu=${pdu.size}")
        }
        // Multi-fragment: if LAST not set, append further reads — rare for share enum.
        val flags = pdu[3].toInt() and 0xff
        if (flags and PFC_LAST == 0) {
            throw IOException("fragmented RPC response not supported for share enum")
        }
        return pdu.copyOfRange(24, 24 + stubLen)
    }

    private fun pipeTransact(pipe: NamedPipe, request: ByteArray): ByteArray {
        // smbj NamedPipe.transact → FSCTL_PIPE_TRANSCEIVE; max out capped by SmbConfig
        // transact buffer (share-enum client uses 64 KiB).
        return try {
            pipe.transact(request)
        } catch (e: SMBApiException) {
            throw IOException("srvsvc pipe transact failed: ${e.status}", e)
        }
    }

    // --- NDR helpers (little-endian, connection-oriented) ---

    internal class NdrWriter {
        private val bos = ByteArrayOutputStream(256)
        private var referentCounter = 1

        fun size(): Int = bos.size()

        fun u8(v: Int) {
            bos.write(v and 0xff)
        }

        fun u16(v: Int) {
            align(2)
            bos.write(v and 0xff)
            bos.write((v ushr 8) and 0xff)
        }

        fun u32(v: Int) {
            align(4)
            bos.write(v and 0xff)
            bos.write((v ushr 8) and 0xff)
            bos.write((v ushr 16) and 0xff)
            bos.write((v ushr 24) and 0xff)
        }

        fun nullPtr() = u32(0)

        fun referent(): Int {
            val id = referentCounter++
            // Non-zero unique referent; value itself is not dereferenced by server for empty containers.
            u32(id)
            return id
        }

        fun raw(bytes: ByteArray) {
            bos.write(bytes)
        }

        fun align(n: Int) {
            val pad = (n - (bos.size() % n)) % n
            repeat(pad) { bos.write(0) }
        }

        fun toByteArray(): ByteArray = bos.toByteArray()
    }

    internal class NdrReader(private val data: ByteArray) {
        private var pos = 0

        fun align(n: Int) {
            val pad = (n - (pos % n)) % n
            pos += pad
        }

        fun u32(): Int {
            align(4)
            if (pos + 4 > data.size) throw IOException("NDR underflow at $pos")
            val v = (data[pos].toInt() and 0xff) or
                ((data[pos + 1].toInt() and 0xff) shl 8) or
                ((data[pos + 2].toInt() and 0xff) shl 16) or
                ((data[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }

        fun readConformantVaryingWString(): String {
            // MaximumCount
            align(4)
            u32()
            // Offset, ActualCount
            align(4)
            val offset = u32()
            val actual = u32()
            if (offset < 0 || actual < 0 || actual > 1_000_000) {
                throw IOException("bad WString offset=$offset actual=$actual")
            }
            align(2)
            pos += offset * 2
            val codePoints = if (actual > 0) actual - 1 else 0 // drop null terminator
            if (pos + actual * 2 > data.size) {
                throw IOException("WString overruns buffer")
            }
            val sb = StringBuilder(codePoints)
            var i = 0
            while (i < codePoints) {
                val ch = (data[pos].toInt() and 0xff) or ((data[pos + 1].toInt() and 0xff) shl 8)
                sb.append(ch.toChar())
                pos += 2
                i++
            }
            if (actual > 0) {
                pos += 2 // null
            }
            return sb.toString()
        }
    }
}
