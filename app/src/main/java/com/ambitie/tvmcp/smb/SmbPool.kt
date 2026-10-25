package com.ambitie.tvmcp.smb

import com.ambitie.tvmcp.data.SmbSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)

data class OpenedSmbFile(
    val file: SmbFile,
    val length: Long
)

class SmbPool {
    private class Handle(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare
    ) {
        fun closeQuietly() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private val handles = HashMap<String, Handle>()
    private val lock = Any()
    private val config = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .build()

    fun test(source: SmbSource) {
        list(source, "")
    }

    fun list(source: SmbSource, dir: String): List<SmbEntry> {
        val path = SmbPath.normalize(dir)
        return withShare(source) { share ->
            share.list(path)
                .asSequence()
                .mapNotNull { entry ->
                    val name = entry.fileName ?: return@mapNotNull null
                    if (name == "." || name == "..") return@mapNotNull null
                    val isDirectory =
                        (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    SmbEntry(
                        name = name,
                        path = if (path.isEmpty()) name else "$path/$name",
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else entry.endOfFile
                    )
                }
                .sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                .toList()
        }
    }

    fun open(source: SmbSource, path: String): OpenedSmbFile {
        val normalized = SmbPath.normalize(path)
        if (normalized.isEmpty()) throw IOException("SMB 文件路径不能为空")
        return withShare(source) { share ->
            val file = share.openFile(
                normalized,
                setOf(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            try {
                OpenedSmbFile(file, file.length)
            } catch (e: Exception) {
                runCatching { file.close() }
                throw e
            }
        }
    }

    fun invalidate(sourceId: String) {
        synchronized(lock) {
            handles.remove(sourceId)?.closeQuietly()
        }
    }

    fun closeAll() {
        synchronized(lock) {
            handles.values.forEach { it.closeQuietly() }
            handles.clear()
        }
    }

    private fun <T> withShare(source: SmbSource, block: (DiskShare) -> T): T {
        val handle = synchronized(lock) {
            handles[source.id] ?: connect(source).also { handles[source.id] = it }
        }
        return try {
            block(handle.share)
        } catch (e: Exception) {
            synchronized(lock) { handles.remove(source.id)?.closeQuietly() }
            if (e is IOException) throw e
            throw IOException("SMB 访问失败（${source.host}/${source.share}）：${e.message}", e)
        }
    }

    private fun connect(source: SmbSource): Handle {
        val client = SMBClient(config)
        try {
            val connection = client.connect(source.host, source.port)
            val auth = if (source.username.isBlank()) {
                // anonymous() 在 SMB 3.1.1 下没有会话密钥，smbj 派生签名密钥时会 NPE；空用户名按 Guest 登录
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(source.username, source.password.toCharArray(), source.domain)
            }
            val session = connection.authenticate(auth)
            val share = session.connectShare(source.share) as? DiskShare
                ?: throw IOException("共享不是磁盘共享：${source.share}")
            return Handle(client, connection, session, share)
        } catch (e: Exception) {
            runCatching { client.close() }
            throw IOException("无法连接 SMB：${source.host}:${source.port}/${source.share}，${e.message}", e)
        }
    }
}
