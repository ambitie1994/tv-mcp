package com.ambitie.tvmcp.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.ambitie.tvmcp.data.SourceStore
import com.ambitie.tvmcp.mcp.ToolException
import com.ambitie.tvmcp.smb.SmbDataSource
import com.ambitie.tvmcp.smb.SmbPool
import com.ambitie.tvmcp.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.ceil

data class SubtitleTrackInfo(
    val index: Int,
    val language: String?,
    val label: String?,
    val mimeType: String?,
    val selected: Boolean
)

data class PlaybackStatus(
    val state: String = "idle",
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val bufferedMs: Long = 0L,
    val title: String? = null,
    val mediaKey: String? = null,
    val queueIndex: Int = 0,
    val queueCount: Int = 0,
    val volume: Float = 1f,
    val error: String? = null
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerManager(
    context: Context,
    private val sources: SourceStore,
    private val history: PlaybackHistory,
    private val pool: SmbPool
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()
    private val _cueText = MutableStateFlow<String?>(null)
    val cueText: StateFlow<String?> = _cueText.asStateFlow()

    val player: ExoPlayer
    private val mediaSession: MediaSession
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var lastSystemVolumeIndex = -1
    private var currentInfo: CurrentMedia? = null
    private var lastKnownPosition = 0L
    private var lastKnownDuration = 0L
    private var lastPersistAt = 0L
    private var lastToggleAt = 0L
    private var lastError: String? = null
    private var pendingSubtitleLabel: String? = null
    private var pendingSubtitleLanguage: String? = null
    private var released = false

    fun play(media: ResolvedMedia) {
        ensureAlive()
        persist(false)
        _cueText.value = null
        lastError = null
        pendingSubtitleLabel = MediaSpec.subtitleLabel(media.item)
        pendingSubtitleLanguage = MediaSpec.subtitleLanguage(media.item)
        currentInfo = MediaSpec.currentMedia(media.item)
        lastKnownPosition = media.startMs
        lastKnownDuration = 0
        lastPersistAt = SystemClock.elapsedRealtime()
        player.setMediaItem(media.item, media.startMs)
        player.prepare()
        player.play()
        emit()
    }

    fun playQueue(items: List<ResolvedMedia>, startIndex: Int, startMs: Long) {
        ensureAlive()
        if (items.isEmpty()) throw ToolException("播放队列为空")
        val index = startIndex.coerceIn(0, items.lastIndex)
        persist(false)
        _cueText.value = null
        lastError = null
        pendingSubtitleLabel = MediaSpec.subtitleLabel(items[index].item)
        pendingSubtitleLanguage = MediaSpec.subtitleLanguage(items[index].item)
        currentInfo = MediaSpec.currentMedia(items[index].item)
        lastKnownPosition = startMs
        lastKnownDuration = 0
        lastPersistAt = SystemClock.elapsedRealtime()
        player.setMediaItems(items.map { it.item }, index, startMs)
        player.prepare()
        player.play()
        emit()
    }

    fun pause() {
        ensureAlive()
        player.pause()
        emit()
    }

    fun resume() {
        ensureAlive()
        requireMedia()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
        emit()
    }

    fun toggle() {
        ensureAlive()
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt < 300) return
        lastToggleAt = now
        if (player.isPlaying) {
            player.pause()
        } else if (player.mediaItemCount > 0) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        emit()
    }

    fun stop() {
        ensureAlive()
        persist(false)
        _cueText.value = null
        player.stop()
        player.clearMediaItems()
        currentInfo = null
        lastKnownPosition = 0
        lastKnownDuration = 0
        lastError = null
        emit()
    }

    fun seekTo(positionMs: Long) {
        ensureAlive()
        requireMedia()
        val position = positionMs.coerceAtLeast(0)
        val duration = player.duration
        player.seekTo(if (duration != C.TIME_UNSET) position.coerceAtMost(duration) else position)
        emit()
    }

    fun seekBy(offsetMs: Long) {
        ensureAlive()
        requireMedia()
        seekTo(player.currentPosition + offsetMs)
    }

    fun next() {
        ensureAlive()
        requireMedia()
        if (!player.hasNextMediaItem()) throw ToolException("已经是最后一集")
        persist(false)
        player.seekToNextMediaItem()
        emit()
    }

    fun previous() {
        ensureAlive()
        requireMedia()
        persist(false)
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0)
        }
        emit()
    }

    fun queueTitles(): List<String> {
        ensureAlive()
        return (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: item.mediaId.takeIf { it.isNotBlank() }
                ?: "第 ${index + 1} 个"
        }
    }

    fun currentQueueIndex(): Int =
        if (player.mediaItemCount > 0) player.currentMediaItemIndex else -1

    fun playQueueItem(index: Int) {
        ensureAlive()
        requireMedia()
        if (index < 0 || index >= player.mediaItemCount) return
        persist(false)
        _cueText.value = null
        player.seekTo(index, 0)
        player.play()
        emit()
    }

    fun setVolume(volume: Float) {
        ensureAlive()
        applyVolume(volume.coerceIn(0f, 1f))
        emit()
    }

    fun effectiveVolume(): Float {
        val manager = audioManager ?: return player.volume
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return player.volume
        val index = syncSystemVolume(manager)
        val systemGain = index.toFloat() / max
        return (systemGain * player.volume).coerceIn(0f, 1f)
    }

    private fun syncSystemVolume(manager: AudioManager): Int {
        val index = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (lastSystemVolumeIndex >= 0 && index != lastSystemVolumeIndex) {
            player.volume = 1f
        }
        lastSystemVolumeIndex = index
        return index
    }

    private fun applyVolume(target: Float) {
        val manager = audioManager
        val max = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        if (manager == null || max <= 0) {
            player.volume = target
            return
        }
        val index = if (target <= 0f) {
            0
        } else {
            ceil(target * max - 0.0001f).toInt().coerceIn(1, max)
        }
        val systemGain = index.toFloat() / max
        val trackVolume = if (index == 0) 0f else (target / systemGain).coerceIn(0f, 1f)
        val applied = runCatching {
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) != index) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            }
            true
        }.getOrDefault(false)
        player.volume = if (applied) trackVolume else target
        lastSystemVolumeIndex = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun currentMediaInfo(): CurrentMedia? = currentInfo

    fun subtitleTracks(): List<SubtitleTrackInfo> = textTrackRefs().mapIndexed { index, ref ->
        val format = ref.group.getTrackFormat(ref.trackIndex)
        SubtitleTrackInfo(
            index = index,
            language = format.language,
            label = format.label,
            mimeType = format.sampleMimeType,
            selected = ref.group.isTrackSelected(ref.trackIndex)
        )
    }

    fun attachedSubtitleUris(): Set<String> =
        player.currentMediaItem?.localConfiguration?.subtitleConfigurations
            ?.mapTo(mutableSetOf()) { it.uri.toString() }
            ?: emptySet()

    fun disableSubtitles() {
        ensureAlive()
        requireMedia()
        pendingSubtitleLabel = null
        pendingSubtitleLanguage = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _cueText.value = null
        emit()
    }

    fun selectSubtitle(index: Int) {
        ensureAlive()
        requireMedia()
        val target = textTrackRefs().getOrNull(index)
            ?: throw ToolException("字幕轨道索引超出范围：$index")
        pendingSubtitleLabel = null
        pendingSubtitleLanguage = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .addOverride(TrackSelectionOverride(target.group.mediaTrackGroup, target.trackIndex))
            .build()
        emit()
    }

    fun attachSubtitle(
        uri: android.net.Uri,
        mimeType: String,
        language: String?,
        label: String
    ) {
        ensureAlive()
        requireMedia()
        val current = player.currentMediaItem ?: throw ToolException("当前没有播放内容")
        val existing = current.localConfiguration?.subtitleConfigurations.orEmpty()
        if (existing.any { it.uri == uri }) {
            val index = textTrackRefs().indexOfFirst {
                it.group.getTrackFormat(it.trackIndex).label == label
            }
            if (index >= 0) selectSubtitle(index)
            return
        }
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage(language)
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updated = current.buildUpon()
            .setSubtitleConfigurations(existing + config)
            .build()
        val position = player.currentPosition.coerceAtLeast(0)
        val index = player.currentMediaItemIndex
        val wasPlaying = player.isPlaying
        pendingSubtitleLabel = label
        pendingSubtitleLanguage = language
        if (player.mediaItemCount > 1) {
            val items = (0 until player.mediaItemCount).map {
                if (it == index) updated else player.getMediaItemAt(it)
            }
            player.setMediaItems(items, index, position)
        } else {
            player.setMediaItem(updated, position)
        }
        player.prepare()
        if (wasPlaying) player.play()
        emit()
    }

    private data class TextTrackRef(val group: Tracks.Group, val trackIndex: Int)

    private fun textTrackRefs(): List<TextTrackRef> {
        val refs = mutableListOf<TextTrackRef>()
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) refs += TextTrackRef(group, index)
        }
        return refs
    }

    private fun selectPendingSubtitle() {
        val label = pendingSubtitleLabel
        val language = pendingSubtitleLanguage
        if (label == null && language == null) return
        val target = textTrackRefs().firstOrNull { ref ->
            val format = ref.group.getTrackFormat(ref.trackIndex)
            (label != null && format.label == label) ||
                (label == null && language != null && format.language == language)
        } ?: return
        pendingSubtitleLabel = null
        pendingSubtitleLanguage = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .addOverride(TrackSelectionOverride(target.group.mediaTrackGroup, target.trackIndex))
            .build()
    }

    fun snapshot(): PlaybackStatus {
        val hasMedia = player.mediaItemCount > 0
        val info = currentInfo ?: MediaSpec.currentMedia(player.currentMediaItem)
        val rawDuration = player.duration
        val state = when {
            !hasMedia -> "idle"
            player.playbackState == Player.STATE_ENDED -> "ended"
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.playbackState == Player.STATE_READY && player.isPlaying -> "playing"
            player.playbackState == Player.STATE_READY -> "paused"
            else -> "idle"
        }
        return PlaybackStatus(
            state = state,
            hasMedia = hasMedia,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = if (rawDuration == C.TIME_UNSET) null else rawDuration,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
            title = info?.title,
            mediaKey = info?.key,
            queueIndex = if (hasMedia) player.currentMediaItemIndex + 1 else 0,
            queueCount = player.mediaItemCount,
            volume = effectiveVolume(),
            error = lastError
        )
    }

    fun release() {
        if (released) return
        if (player.mediaItemCount > 0) persist(false)
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { mediaSession.release() }
        player.removeListener(listener)
        runCatching { player.release() }
    }

    private fun ensureAlive() {
        if (released) throw ToolException("播放器已释放")
    }

    private fun requireMedia() {
        if (player.mediaItemCount == 0) throw ToolException("当前没有播放内容")
    }

    private fun emit() {
        if (!released) _status.value = snapshot()
    }

    private fun persist(
        completed: Boolean,
        positionOverride: Long? = null,
        durationOverride: Long? = null
    ) {
        val info = currentInfo ?: return
        val position = (positionOverride ?: player.currentPosition).coerceAtLeast(0)
        if (!completed && position < 1_000) return
        var duration = durationOverride?.takeIf { it > 0 }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: lastKnownDuration
        if (completed && duration <= 0) duration = position
        val reachedEnd = duration > 0 && position >= duration - 1_000
        history.save(
            HistoryEntry(
                key = info.key,
                title = info.title,
                positionMs = position,
                durationMs = duration,
                updatedAt = System.currentTimeMillis(),
                completed = completed || reachedEnd,
                sourceId = info.sourceId,
                path = info.path,
                url = info.url
            )
        )
        lastPersistAt = SystemClock.elapsedRealtime()
    }

    private fun applyMarkers() {
        val item = player.currentMediaItem ?: return
        val intro = MediaSpec.introEndMs(item)
        val outro = MediaSpec.outroStartMs(item)
        var position = player.currentPosition

        if (MediaSpec.autoSkipIntro(item) && intro > 0 &&
            lastKnownPosition < intro && position >= intro
        ) {
            player.seekTo(intro)
            position = intro
        }

        if (MediaSpec.autoSkipOutro(item) && outro > 0 &&
            lastKnownPosition < outro && position >= outro
        ) {
            if (player.hasNextMediaItem()) {
                persist(false, outro, lastKnownDuration)
                player.seekToNextMediaItem()
                position = 0
            } else {
                val duration = player.duration
                if (duration != C.TIME_UNSET && duration > 0) {
                    player.seekTo(duration)
                } else {
                    player.stop()
                }
                position = player.currentPosition
            }
        }

        lastKnownPosition = position
    }

    private val ticker: Runnable by lazy {
        object : Runnable {
            override fun run() {
                if (released) return
                try {
                    if (player.mediaItemCount > 0) {
                        val duration = player.duration
                        if (duration != C.TIME_UNSET && duration > 0) lastKnownDuration = duration
                        if (player.isPlaying) {
                            applyMarkers()
                            if (SystemClock.elapsedRealtime() - lastPersistAt >= PERSIST_INTERVAL_MS) {
                                persist(false)
                            }
                        }
                        emit()
                    } else if (_status.value.hasMedia) {
                        emit()
                    }
                } finally {
                    if (!released) mainHandler.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    private val listener: Player.Listener by lazy {
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    persist(completed = true, positionOverride = player.currentPosition)
                }
                emit()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.playbackState != Player.STATE_ENDED) {
                    persist(false)
                }
                emit()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (currentInfo != null) {
                    persist(false, lastKnownPosition, lastKnownDuration)
                }
                pendingSubtitleLabel = MediaSpec.subtitleLabel(mediaItem)
                pendingSubtitleLanguage = MediaSpec.subtitleLanguage(mediaItem)
                currentInfo = MediaSpec.currentMedia(mediaItem)
                lastKnownPosition = player.currentPosition.coerceAtLeast(0)
                lastKnownDuration = 0
                lastError = null
                emit()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    lastKnownPosition = newPosition.positionMs
                }
                emit()
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = generateSequence(error.cause) { it.cause }.lastOrNull()
                lastError = cause?.message?.takeIf { it.isNotBlank() }
                    ?: error.message
                    ?: error.errorCodeName
                persist(false)
                emit()
            }

            override fun onTracksChanged(tracks: Tracks) {
                selectPendingSubtitle()
                emit()
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                val text = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                    .ifBlank { null }
                _cueText.value = text
            }
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PlayerManager must be created on the main thread"
        }

        val dataSourceFactory = SmbDataSource.Factory(context, sources, pool)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        player = ExoPlayer.Builder(context, mediaSourceFactory)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 30_000, 2_500, 5_000)
                    .build()
            )
            .build()
        player.volume = 1f
        val preferredTextLanguages = linkedSetOf("zh", Locale.getDefault().language)
            .filter { it.isNotBlank() }
            .toTypedArray()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*preferredTextLanguages)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        mediaSession = MediaSession.Builder(context, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_SHOW_PLAYER, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        player.addListener(listener)
        mainHandler.post(ticker)
    }

    companion object {
        private const val TICK_MS = 500L
        private const val PERSIST_INTERVAL_MS = 15_000L
    }
}
