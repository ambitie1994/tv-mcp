package com.ambitie.tvmcp.smb

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.ambitie.tvmcp.data.SourceStore
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SmbDataSource(
    private val context: Context,
    private val sources: SourceStore,
    private val pool: SmbPool
) : BaseDataSource(true) {

    private var fallback: DataSource? = null
    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false
    private var readBuffer: ByteArray? = null
    private var bufferOffset = 0
    private var bufferLength = 0

    override fun open(dataSpec: DataSpec): Long {
        close()
        if (!SCHEME.equals(dataSpec.uri.scheme, ignoreCase = true)) {
            val delegate = DefaultDataSource.Factory(context).createDataSource()
            fallback = delegate
            return delegate.open(dataSpec)
        }
        transferInitializing(dataSpec)

        val sourceId = dataSpec.uri.host
            ?: throw IOException("SMB URI 缺少片源 id")
        val source = sources.get(sourceId)
            ?: throw IOException("未找到片源：$sourceId")
        val path = dataSpec.uri.pathSegments.joinToString("/")
        if (path.isEmpty()) throw IOException("SMB URI 缺少文件路径")

        val opened = pool.open(source, path)
        val length = opened.length
        if (dataSpec.position < 0 || dataSpec.position > length) {
            runCatching { opened.file.close() }
            throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        }

        file = opened.file
        this.uri = dataSpec.uri
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, length - position)
        } else {
            length - position
        }
        readBuffer = ByteArray(BUFFER_SIZE)
        bufferOffset = 0
        bufferLength = 0
        started = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val smbFile = file ?: throw IOException("SMB DataSource 尚未打开")

        try {
            if (bufferLength == 0 || bufferOffset >= bufferLength) {
                fillBuffer(smbFile)
            }
            if (bufferLength <= 0) return C.RESULT_END_OF_INPUT

            val available = (bufferLength - bufferOffset).toLong()
            val count = minOf(length.toLong(), available, remaining).toInt()
            System.arraycopy(readBuffer!!, bufferOffset, buffer, offset, count)
            bufferOffset += count
            position += count
            remaining -= count
            bytesTransferred(count)
            return count
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("SMB 读取失败：${e.message}", e)
        }
    }

    private fun fillBuffer(smbFile: SmbFile) {
        val buffer = readBuffer ?: return
        val count = minOf(buffer.size.toLong(), remaining).toInt()
        if (count <= 0) {
            bufferLength = 0
            return
        }
        val read = smbFile.read(buffer, position, 0, count)
        if (read <= 0) {
            bufferLength = 0
            return
        }
        bufferOffset = 0
        bufferLength = read
    }

    override fun close() {
        val delegate = fallback
        fallback = null
        if (delegate != null) runCatching { delegate.close() }
        val current = file
        file = null
        if (current != null) runCatching { current.close() }
        uri = null
        position = 0
        remaining = 0
        bufferOffset = 0
        bufferLength = 0
        if (started) {
            started = false
            transferEnded()
        }
    }

    override fun getUri(): Uri? = fallback?.uri ?: uri

    class Factory(
        private val context: Context,
        private val sources: SourceStore,
        private val pool: SmbPool
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(context, sources, pool)
    }

    companion object {
        const val SCHEME = "tvsmb"
        private const val BUFFER_SIZE = 1 shl 20
    }
}
