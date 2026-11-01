package com.ambitie.tvmcp.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.ambitie.tvmcp.R
import com.ambitie.tvmcp.TvMcpApp
import com.ambitie.tvmcp.core.TvMcpCore
import com.ambitie.tvmcp.data.AiListItem
import com.ambitie.tvmcp.data.AiListStore
import com.ambitie.tvmcp.data.CachedChannel
import com.ambitie.tvmcp.data.IptvSource
import com.ambitie.tvmcp.data.SmbSource
import com.ambitie.tvmcp.iptv.IptvChannel
import com.ambitie.tvmcp.iptv.M3uLoader
import com.ambitie.tvmcp.iptv.M3uParser
import com.ambitie.tvmcp.mcp.ToolException
import com.ambitie.tvmcp.playback.HistoryEntry
import com.ambitie.tvmcp.playback.MediaFiles
import com.ambitie.tvmcp.playback.MediaSpec
import com.ambitie.tvmcp.playback.PlaybackStatus
import com.ambitie.tvmcp.playback.PlayerManager
import com.ambitie.tvmcp.playback.SubtitleFile
import com.ambitie.tvmcp.service.McpServerService
import com.ambitie.tvmcp.smb.SmbEntry
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MainActivity : AppCompatActivity() {

    private enum class Screen {
        HOME, BROWSE, CHANNELS, AI_LIST, PAIRING, IPTV_MANAGE, SMB_MANAGE
    }

    private enum class HudMode { SEEK, VOLUME }

    private enum class MenuAction { HOME, LIVE, NAS, AI, PAIRING }

    private lateinit var core: TvMcpCore
    private lateinit var root: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var browseView: ScrollView
    private lateinit var channelsView: ScrollView
    private lateinit var aiListView: ScrollView
    private lateinit var pairingView: ScrollView
    private lateinit var iptvManageView: ScrollView
    private lateinit var smbManageView: ScrollView
    private lateinit var homeContent: LinearLayout
    private lateinit var browseContent: LinearLayout
    private lateinit var channelsContent: LinearLayout
    private lateinit var aiListContent: LinearLayout
    private lateinit var pairingContent: LinearLayout
    private lateinit var iptvManageContent: LinearLayout
    private lateinit var smbManageContent: LinearLayout

    private lateinit var playerOverlay: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var subtitleOverlay: TextView
    private var cueText: String? = null
    private lateinit var playerHud: LinearLayout
    private lateinit var hudTitle: TextView
    private lateinit var hudValue: TextView
    private lateinit var hudProgress: PlayerProgressView
    private lateinit var hudTimeLeft: TextView
    private lateinit var hudTimeRight: TextView
    private lateinit var hudFooter: LinearLayout
    private lateinit var menuOverlay: FrameLayout
    private lateinit var sideMenu: LinearLayout
    private lateinit var menuScroll: CappedScrollView
    private lateinit var menuContent: LinearLayout
    private lateinit var hudControls: LinearLayout
    private lateinit var hudPlayPause: Button
    private lateinit var playlistPanel: LinearLayout
    private lateinit var playlistScroll: ScrollView
    private lateinit var playlistList: LinearLayout
    private var lastPlaylistSig: String? = null
    private lateinit var touchMenuButton: Button
    private var touchHintShown = false
    private val hasTouchscreen by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private var screen = Screen.HOME
    private var browseSourceId: String? = null
    private var browsePath = ""
    private var browseLoading = false
    private var browseError: String? = null
    private var browseEntries: List<SmbEntry> = emptyList()
    private var browseJob: Job? = null

    private var channelSourceId: String? = null
    private var channels: List<CachedChannel> = emptyList()
    private var channelsLoading = false
    private var channelsError: String? = null
    private var channelsJob: Job? = null
    private var autoPlayFirstChannel = false
    private var channelPage = 0

    private var playerHidden = false
    private var playerReturnScreen = Screen.HOME
    private var overlayShown = false
    private var lastStatus: PlaybackStatus? = null
    private var lastMediaKey: String? = null
    private var lastErrorShown: String? = null
    private var lastPlaybackSignature: String? = null
    private var endedHudKey: String? = null
    private var hudVisible = false
    private var hudMode = HudMode.SEEK
    private var hudHint: String? = null
    private var lastSeekKeyAt = 0L
    private var lastVolumeKeyAt = 0L
    private var menuVisible = false
    private var subtitleMenuVisible = false
    private var menuResumePlayback = false
    private var focusBeforeMenu: View? = null
    private var autoResumeDone = false
    private var lastHomeBackAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideHudRunnable: Runnable? = null
    private var qrBitmap: Bitmap? = null
    private var qrBitmapUrl: String? = null
    private var lastFirstFocus: View? = null
    private var lastSourceVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        core = (application as TvMcpApp).core
        playerHidden = !intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false)
        lastSourceVersion = core.sourceVersion.value
        buildUi()
        setupBackHandling()
        showScreen(Screen.HOME)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
        }

        startServerService()
        mainHandler.postDelayed({ autoResumeLast() }, 350)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { core.serverState.collect { onServerStateChanged() } }
                launch { core.playbackState.collect { renderPlayback(it) } }
                launch { core.cueText.collect { renderCue(it) } }
                launch {
                    core.sourceVersion.collect { version ->
                        if (version != lastSourceVersion) {
                            lastSourceVersion = version
                            applySubtitleStyle()
                            onSourcesChanged()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        renderPlayback(lastStatus)
    }

    override fun onStop() {
        isVisible = false
        hideHudNow()
        detachPlayer()
        overlayShown = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        browseJob?.cancel()
        channelsJob?.cancel()
        cancelHudHide()
        detachPlayer()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.PAIRING -> renderPairing()
            else -> Unit
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false)) {
            playerHidden = false
            renderPlayback(lastStatus)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.repeatCount == 0) toggleMenu()
                return true
            }
            if (menuVisible) return super.dispatchKeyEvent(event)

            val manager = core.playerOrNull()
            val overlayVisible = playerOverlay.visibility == View.VISIBLE
            val navigation = event.keyCode in NAVIGATION_KEYS

            if (navigation && !hasUsableFocus()) {
                if (overlayVisible) {
                    playerView.post { playerView.requestFocus() }
                } else {
                    val target = lastFirstFocus
                    if (target != null && target.isShown) target.post { target.requestFocus() }
                }
                return true
            }

            if (manager != null && overlayVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.repeatCount == 0) togglePlayback(manager)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekByStep(manager, -SEEK_STEP_MS, event)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekByStep(manager, SEEK_STEP_MS, event)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        adjustVolume(manager, 1, event)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        adjustVolume(manager, -1, event)
                        return true
                    }
                }
            }

            if (manager != null && handleMediaKey(manager, event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleMediaKey(manager: PlayerManager, event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> togglePlayback(manager)
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                runCatching { manager.resume() }
                showHud(HudMode.SEEK, "播放中")
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                runCatching { manager.pause() }
                showHud(HudMode.SEEK, "已暂停")
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekByStep(manager, 30_000, event)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekByStep(manager, -30_000, event)
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                runCatching { manager.next() }
                showHud(HudMode.SEEK)
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                runCatching { manager.previous() }
                showHud(HudMode.SEEK)
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> manager.stop()
            else -> return false
        }
        return true
    }

    private fun seekByStep(manager: PlayerManager, deltaMs: Long, event: KeyEvent) {
        if (event.repeatCount > 0 && event.eventTime - lastSeekKeyAt < SEEK_REPEAT_MS) return
        lastSeekKeyAt = event.eventTime
        applySeek(manager, deltaMs)
    }

    private fun applySeek(manager: PlayerManager, deltaMs: Long) {
        runCatching { manager.seekBy(deltaMs) }
        val seconds = kotlin.math.abs(deltaMs) / 1000
        showHud(HudMode.SEEK, if (deltaMs > 0) "快进 $seconds 秒" else "快退 $seconds 秒")
    }

    private fun adjustVolume(manager: PlayerManager, direction: Int, event: KeyEvent) {
        if (event.repeatCount > 0 && event.eventTime - lastVolumeKeyAt < VOLUME_REPEAT_MS) return
        lastVolumeKeyAt = event.eventTime
        changeVolume(manager, direction)
    }

    private fun changeVolume(manager: PlayerManager, direction: Int, step: Float = VOLUME_STEP) {
        val target = (manager.snapshot().volume + direction * step).coerceIn(0f, 1f)
        runCatching { manager.setVolume(target) }
        showHud(HudMode.VOLUME)
    }

    private fun hudButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setBackgroundResource(R.drawable.touch_control_bg)
        setTextColor(color(R.color.text))
        textSize = 11f
        isAllCaps = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(4), 0, dp(4), 0)
        isFocusable = false
        isFocusableInTouchMode = false
        setOnClickListener { onClick() }
    }

    private fun hudControlParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        }

    private fun togglePlayback(manager: PlayerManager) {
        val wasPlaying = manager.snapshot().isPlaying
        runCatching { manager.toggle() }
        showHud(HudMode.SEEK, if (wasPlaying) "已暂停" else "播放中")
    }

    private fun playlistVisible(): Boolean =
        ::playlistPanel.isInitialized && playlistPanel.visibility == View.VISIBLE

    private fun togglePlaylist() {
        if (playlistVisible()) {
            hidePlaylist()
        } else {
            val manager = core.playerOrNull() ?: return
            renderPlaylist(manager)
            playlistPanel.visibility = View.VISIBLE
        }
        resetHudTimer()
    }

    private fun hidePlaylist() {
        if (::playlistPanel.isInitialized) {
            playlistPanel.visibility = View.GONE
            lastPlaylistSig = null
        }
    }

    private fun renderPlaylist(manager: PlayerManager) {
        val titles = manager.queueTitles()
        val current = manager.currentQueueIndex()
        if (titles.isEmpty()) {
            hidePlaylist()
            return
        }
        playlistList.removeAllViews()
        titles.forEachIndexed { index, title ->
            val isCurrent = index == current
            val row = Button(this).apply {
                text = if (isCurrent) "▶ $title" else "${index + 1}. $title"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isCurrent) 0xFFFFC542.toInt() else color(R.color.text))
                if (isCurrent) setBackgroundResource(R.drawable.touch_control_bg)
                minHeight = dp(40)
                minimumHeight = dp(40)
                setPadding(dp(8), 0, dp(8), 0)
                isFocusable = false
                isFocusableInTouchMode = false
                setOnClickListener {
                    core.playerOrNull()?.let { pm ->
                        runCatching { pm.playQueueItem(index) }
                        renderPlaylist(pm)
                        showHud(HudMode.SEEK)
                    }
                }
            }
            playlistList.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        playlistScroll.post {
            val target = playlistList.getChildAt(current.coerceIn(0, titles.lastIndex)) ?: return@post
            playlistScroll.smoothScrollTo(0, (target.top - dp(24)).coerceAtLeast(0))
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        menuVisible && subtitleMenuVisible -> {
                            subtitleMenuVisible = false
                            renderMenu()
                            focusFirstMenuButton()
                        }
                        menuVisible && playerOverlay.visibility == View.VISIBLE -> closeMenu()
                        menuVisible && screen != Screen.HOME -> {
                            goHome(resume = false)
                            menuContent.getChildAt(0)?.let { first ->
                                first.post { if (menuVisible) first.requestFocus() }
                            }
                        }
                        menuVisible -> closeMenu()
                        playerOverlay.visibility == View.VISIBLE && playlistVisible() -> hidePlaylist()
                        playerOverlay.visibility == View.VISIBLE &&
                            hudVisible && lastStatus?.state == "ended" ->
                            leavePlayerTo(playerReturnScreen)
                        playerOverlay.visibility == View.VISIBLE && hudVisible -> hideHud()
                        playerOverlay.visibility == View.VISIBLE -> leavePlayerTo(playerReturnScreen)
                        screen == Screen.BROWSE -> navigateBrowseUp()
                        screen == Screen.CHANNELS -> goHome()
                        screen == Screen.AI_LIST -> goHome()
                        screen == Screen.PAIRING -> goHome()
                        screen == Screen.IPTV_MANAGE -> goHome()
                        screen == Screen.SMB_MANAGE -> goHome()
                        screen == Screen.HOME -> {
                            val now = System.currentTimeMillis()
                            if (now - lastHomeBackAt <= 2_000L) {
                                exitToDesktop()
                            } else {
                                lastHomeBackAt = now
                                toast("再按一次返回退出应用")
                            }
                        }
                        else -> exitToDesktop()
                    }
                }
            }
        )
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }

        homeView = screenScroll()
        homeView.setBackgroundColor(Color.BLACK)
        homeContent = screenContent()
        homeView.addView(homeContent)
        root.addView(homeView, matchParent())

        browseView = screenScroll()
        browseContent = screenContent()
        browseView.addView(browseContent)
        root.addView(browseView, matchParent())

        channelsView = screenScroll()
        channelsContent = screenContent()
        channelsView.addView(channelsContent)
        root.addView(channelsView, matchParent())

        aiListView = screenScroll()
        aiListContent = screenContent()
        aiListView.addView(aiListContent)
        root.addView(aiListView, matchParent())

        pairingView = screenScroll()
        pairingContent = screenContent()
        pairingView.addView(pairingContent)
        root.addView(pairingView, matchParent())

        iptvManageView = screenScroll()
        iptvManageContent = screenContent()
        iptvManageView.addView(iptvManageContent)
        root.addView(iptvManageView, matchParent())

        smbManageView = screenScroll()
        smbManageContent = screenContent()
        smbManageView.addView(smbManageContent)
        root.addView(smbManageView, matchParent())

        touchMenuButton = Button(this).apply {
            text = "菜单"
            setBackgroundResource(R.drawable.touch_menu_bg)
            setTextColor(color(R.color.text))
            textSize = 13f
            isAllCaps = false
            minWidth = dp(68)
            minimumWidth = dp(68)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(14), 0, dp(14), 0)
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = View.GONE
            setOnClickListener { toggleMenu() }
        }
        root.addView(
            touchMenuButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(16)
                bottomMargin = dp(16)
            }
        )

        playerOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            setShowSubtitleButton(false)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        playerView.subtitleView?.visibility = View.GONE
        if (hasTouchscreen) {
            playerView.setOnClickListener {
                when {
                    playlistVisible() -> hidePlaylist()
                    hudVisible -> hideHud()
                    else -> showHud(HudMode.SEEK)
                }
            }
        }
        playerOverlay.addView(playerView, matchParent())
        subtitleOverlay = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setLineSpacing(0f, 1.12f)
            includeFontPadding = false
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        playerOverlay.addView(
            subtitleOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        applySubtitleStyle()

        playerHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(10))
            setBackgroundResource(R.drawable.player_hud_bg)
            visibility = View.GONE
            isFocusable = false
        }
        val hudHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hudTitle = textView("", 15f, R.color.text, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        hudHeader.addView(
            hudTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        hudValue = textView("", 14f, R.color.accent_focus, bold = true).apply {
            maxLines = 1
            gravity = Gravity.END
        }
        hudHeader.addView(
            hudValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(16) }
        )
        playerHud.addView(hudHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        hudProgress = PlayerProgressView(this)
        playerHud.addView(
            hudProgress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(18)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(2)
            }
        )
        hudFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hudTimeLeft = textView("", 12f, R.color.text_dim)
        hudTimeRight = textView("", 12f, R.color.text_dim)
        hudFooter.addView(
            hudTimeLeft,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        hudFooter.addView(
            View(this),
            LinearLayout.LayoutParams(0, dp(1), 1f)
        )
        hudFooter.addView(
            hudTimeRight,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        playerHud.addView(
            hudFooter,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        hudControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (hasTouchscreen) View.VISIBLE else View.GONE
        }
        hudPlayPause = hudButton("播放") {
            core.playerOrNull()?.let { togglePlayback(it) }
        }
        hudControls.addView(hudButton("-10s") {
            core.playerOrNull()?.let { applySeek(it, -SEEK_STEP_MS) }
        }, hudControlParams())
        hudControls.addView(hudPlayPause, hudControlParams())
        hudControls.addView(hudButton("+10s") {
            core.playerOrNull()?.let { applySeek(it, SEEK_STEP_MS) }
        }, hudControlParams())
        hudControls.addView(hudButton("列表") {
            togglePlaylist()
        }, hudControlParams())
        hudControls.addView(hudButton("菜单") {
            openMenu()
        }, hudControlParams())
        playerHud.addView(
            hudControls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(6) }
        )
        playerOverlay.addView(
            playerHud,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(80)
                rightMargin = dp(80)
                bottomMargin = dp(36)
            }
        )
        playlistList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        playlistScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(
                playlistList,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        playlistPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundResource(R.drawable.playlist_bg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isFocusable = false
            isFocusableInTouchMode = false
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        textView("播放列表", 14f, R.color.text, bold = true),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    if (hasTouchscreen) {
                        addView(
                            hudButton("关闭") { hidePlaylist() },
                            LinearLayout.LayoutParams(dp(56), dp(36))
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            )
            addView(
                playlistScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        playerOverlay.addView(
            playlistPanel,
            FrameLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
                topMargin = dp(8)
                rightMargin = dp(10)
                bottomMargin = dp(70)
            }
        )
        root.addView(playerOverlay, matchParent())

        menuOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isFocusable = false
        }
        menuOverlay.addView(
            View(this).apply {
                setBackgroundColor(Color.argb(0, 0, 0, 0))
                isClickable = true
                setOnClickListener { closeMenu() }
            },
            matchParent()
        )
        sideMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(16))
            setBackgroundResource(R.drawable.side_menu_bg)
        }
        sideMenu.addView(
            textView("菜单", 22f, R.color.text, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sideMenu.addView(
            textView("TV MCP", 12f, R.color.text_dim).apply {
                setPadding(0, dp(2), 0, dp(14))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        menuContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        menuScroll = CappedScrollView(
            this,
            resources.displayMetrics.heightPixels - dp(200)
        ).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(
                menuContent,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        sideMenu.addView(
            menuScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sideMenu.addView(
            textView(
                if (hasTouchscreen) "点击空白处关闭" else "MENU / BACK 关闭",
                11f,
                R.color.text_dim
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        menuOverlay.addView(
            sideMenu,
            FrameLayout.LayoutParams(
                dp(308),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP
            ).apply {
                leftMargin = dp(28)
                topMargin = dp(24)
            }
        )
        root.addView(menuOverlay, matchParent())

        setContentView(root)
    }

    private fun screenScroll(): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(color(R.color.bg))
        visibility = View.GONE
    }

    private fun screenContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(48), dp(32), dp(48), dp(if (hasTouchscreen) 96 else 48))
    }

    private fun showScreen(target: Screen) {
        screen = target
        lastHomeBackAt = 0L
        homeView.visibility = if (target == Screen.HOME) View.VISIBLE else View.GONE
        browseView.visibility = if (target == Screen.BROWSE) View.VISIBLE else View.GONE
        channelsView.visibility = if (target == Screen.CHANNELS) View.VISIBLE else View.GONE
        aiListView.visibility = if (target == Screen.AI_LIST) View.VISIBLE else View.GONE
        pairingView.visibility = if (target == Screen.PAIRING) View.VISIBLE else View.GONE
        iptvManageView.visibility = if (target == Screen.IPTV_MANAGE) View.VISIBLE else View.GONE
        smbManageView.visibility = if (target == Screen.SMB_MANAGE) View.VISIBLE else View.GONE
        when (target) {
            Screen.HOME -> renderHome()
            Screen.BROWSE -> renderBrowse()
            Screen.CHANNELS -> renderChannels()
            Screen.AI_LIST -> renderAiList()
            Screen.PAIRING -> renderPairing()
            Screen.IPTV_MANAGE -> renderIptvManage()
            Screen.SMB_MANAGE -> renderSmbManage()
        }
        updateTouchMenuButton()
    }

    private fun updateTouchMenuButton() {
        if (!::touchMenuButton.isInitialized || !::playerOverlay.isInitialized) return
        val show = hasTouchscreen && !menuVisible && playerOverlay.visibility != View.VISIBLE
        touchMenuButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun onServerStateChanged() {
        if (menuVisible || playerOverlay.visibility == View.VISIBLE) return
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.PAIRING -> renderPairing()
            else -> Unit
        }
    }

    private fun onSourcesChanged() {
        if (menuVisible || playerOverlay.visibility == View.VISIBLE) return
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.BROWSE -> renderBrowse()
            Screen.CHANNELS -> renderChannels()
            Screen.AI_LIST -> renderAiList()
            Screen.IPTV_MANAGE -> renderIptvManage()
            Screen.SMB_MANAGE -> renderSmbManage()
            Screen.PAIRING -> Unit
        }
    }

    // ---------------- Home ----------------

    private fun renderHome() {
        homeContent.removeAllViews()
        lastFirstFocus = null
        var first: View? = null
        val items = core.aiList.items()
        if (items.isEmpty()) {
            homeContent.addView(
                textView("没有正在播放的内容\n按 MENU 选择电视源或片源", 16f, R.color.text_dim).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(200), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        } else {
            pageTitle(homeContent, core.aiList.name(), "AI 下发 · 按 MENU 打开菜单")
            items.forEach { item ->
                val detail = if (item.invalid) {
                    "失效：${item.lastError ?: "播放失败"}"
                } else {
                    item.group?.ifBlank { "AI 下发" } ?: "AI 下发"
                }
                val row = addRow(homeContent, item.title, detail) {
                    playAiItem(item, returnScreen = Screen.HOME)
                }
                if (first == null) first = row
            }
            val invalid = items.count { it.invalid }
            if (invalid > 0) {
                addRow(homeContent, "清理失效 AI 项", "$invalid 项") {
                    val removed = core.aiList.removeInvalid()
                    core.bumpSourceVersion()
                    renderHome()
                    toast("已移除 $removed 项")
                }
            }
        }
        focusLater(first)
    }

    private fun autoResumeLast() {
        if (autoResumeDone) return
        autoResumeDone = true
        if (core.playerOrNull()?.snapshot()?.hasMedia == true) return
        val entry = core.history.recent(20).firstOrNull {
            !it.completed && (it.durationMs <= 0 || it.positionMs < it.durationMs - 1_000)
        }
        if (entry == null) {
            if (!menuVisible && screen == Screen.HOME) openMenu()
            return
        }
        resumeHistory(entry)
    }

    private fun goHome(resume: Boolean = true) {
        showScreen(Screen.HOME)
        val manager = core.playerOrNull()
        val status = manager?.snapshot()
        if (status?.hasMedia == true) {
            playerHidden = false
            if (resume && !status.isPlaying && status.state != "ended") {
                runCatching { manager.resume() }
            }
            renderPlayback(manager.snapshot())
        }
    }

    private fun resumeHistory(entry: HistoryEntry) {
        if (entry.url == null && (entry.sourceId == null || entry.path == null)) {
            toast("这条记录缺少片源信息")
            return
        }
        val args = buildJsonObject {
            entry.sourceId?.let { put("source_id", it) }
            entry.path?.let { put("path", it) }
            entry.url?.let { put("url", it) }
            put("title", entry.title)
            put("resume", true)
        }
        playMedia(args, returnScreen = Screen.HOME)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            toast("请在电视设置中授予“显示在其他应用上层”")
        }
    }

    private fun renderPairing() {
        pairingContent.removeAllViews()
        pageTitle(pairingContent, "连接 AI", "扫码连接 AI，或扫码在手机上配置片源")
        val server = core.serverState.value
        val url = if (server.running) core.urls().firstOrNull { !it.contains("<") } else null
        var first: View? = null

        if (url != null) {
            fun qrColumn(qrUrl: String, label: String): LinearLayout {
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(6), 0, dp(12))
                }
                val bitmap = runCatching { makeQr(qrUrl) }.getOrNull()
                if (bitmap != null) {
                    column.addView(ImageView(this).apply {
                        setBackgroundColor(Color.WHITE)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(bitmap)
                    }, LinearLayout.LayoutParams(dp(160), dp(160)))
                }
                column.addView(
                    textView(label, 13f, R.color.text_dim).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, 0)
                    }
                )
                return column
            }

            val qrRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            qrRow.addView(
                qrColumn(url, "扫码连接 AI（含 Token）"),
                LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(24)
                }
            )
            val setupUrl = core.setupUrls().firstOrNull { !it.contains("<") }
            if (setupUrl != null) {
                qrRow.addView(
                    qrColumn(setupUrl, "扫码在手机上配置片源"),
                    LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
            pairingContent.addView(qrRow)
            pairingContent.addView(
                textView("MCP：${url.substringBefore("?token=")}", 12f, R.color.text_dim).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(4))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val copy = rowButton("复制 MCP 地址").apply {
                setOnClickListener { copyUrl(url) }
            }
            actionRow.addView(
                copy,
                LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(8) }
            )
            actionRow.addView(
                rowButton("重新生成 Token").apply {
                    setOnClickListener {
                        core.rotateToken()
                        renderPairing()
                        toast("Token 已更新")
                    }
                },
                LinearLayout.LayoutParams(0, dp(72), 1f)
            )
            pairingContent.addView(
                actionRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
            first = copy
        } else {
            first = addRow(
                pairingContent,
                "启动 MCP 服务",
                if (server.error != null) server.error else "启动后才能生成二维码"
            ) { startServerService() }
        }

        if (!Settings.canDrawOverlays(this)) {
            addRow(
                pairingContent,
                "允许后台弹出画面",
                "需要在系统设置中授权"
            ) { openOverlaySettings() }
        }
        focusLater(first)
    }

    private fun renderIptvManage() {
        iptvManageContent.removeAllViews()
        pageTitle(iptvManageContent, "电视源", "M3U / M3U8 播放列表")
        val list = core.iptv.all()
        var first: View? = null
        if (list.isEmpty()) {
            addText(iptvManageContent, "还没有电视源")
        }
        list.forEach { source ->
            val detail = if (source.invalid) "失效：${source.lastError ?: "读取失败"}" else source.url
            val row = addRow(iptvManageContent, source.name, detail) {
                showIptvOptions(source)
            }
            if (first == null) first = row
        }
        val invalid = list.filter { it.invalid }
        if (invalid.isNotEmpty()) {
            addRow(iptvManageContent, "清理失效电视源", "${invalid.size} 个") {
                confirmCleanupIptvSources(invalid)
            }
        }
        val add = addRow(iptvManageContent, "添加电视源", "名称 + M3U / M3U8 地址") {
            showIptvDialog(null)
        }
        if (first == null) first = add
        focusLater(first)
    }

    private fun showIptvOptions(source: IptvSource) {
        AlertDialog.Builder(this)
            .setTitle(source.name)
            .setItems(arrayOf("打开频道", "检测", "编辑", "删除")) { _, which ->
                when (which) {
                    0 -> openChannels(source.id)
                    1 -> testIptv(source)
                    2 -> showIptvDialog(source)
                    3 -> confirmDeleteIptv(source)
                }
            }
            .show()
    }

    private fun renderSmbManage() {
        smbManageContent.removeAllViews()
        pageTitle(smbManageContent, "NAS 片源", "SMB2 / SMB3 共享")
        val list = core.sources.all()
        var first: View? = null
        if (list.isEmpty()) {
            addText(smbManageContent, "还没有 NAS 片源")
        }
        list.forEach { source ->
            val detail = if (source.invalid) {
                "失效：${source.lastError ?: "连接失败"}"
            } else {
                "${source.host}:${source.port}/${source.share}"
            }
            val row = addRow(smbManageContent, source.name, detail) {
                showSmbOptions(source)
            }
            if (first == null) first = row
        }
        val invalid = list.filter { it.invalid }
        if (invalid.isNotEmpty()) {
            addRow(smbManageContent, "清理失效 NAS 源", "${invalid.size} 个") {
                confirmCleanupSmbSources(invalid)
            }
        }
        val add = addRow(smbManageContent, "添加片源", "名称 + 主机 + 共享名 + 账号") {
            showSourceDialog(null)
        }
        if (first == null) first = add
        focusLater(first)
    }

    private fun showSmbOptions(source: SmbSource) {
        AlertDialog.Builder(this)
            .setTitle(source.name)
            .setItems(arrayOf("浏览", "检测", "编辑", "删除")) { _, which ->
                when (which) {
                    0 -> openBrowse(source.id, "")
                    1 -> testSource(source)
                    2 -> showSourceDialog(source)
                    3 -> confirmDelete(source)
                }
            }
            .show()
    }

    private fun confirmDeleteIptv(source: IptvSource) {
        AlertDialog.Builder(this)
            .setTitle("删除电视源")
            .setMessage("确认删除「${source.name}」？")
            .setPositiveButton("删除") { _, _ ->
                core.iptv.delete(source.id)
                core.iptvChannels.removeSource(source.id)
                core.bumpSourceVersion()
                renderIptvManage()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(source: SmbSource) {
        AlertDialog.Builder(this)
            .setTitle("删除片源")
            .setMessage("确认删除「${source.name}」？")
            .setPositiveButton("删除") { _, _ ->
                core.sources.delete(source.id)
                core.smbPool.invalidate(source.id)
                renderSmbManage()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmCleanupIptvSources(sources: List<IptvSource>) {
        if (sources.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清理失效电视源")
            .setMessage("确认移除 ${sources.size} 个失效电视源？")
            .setPositiveButton("移除") { _, _ ->
                sources.forEach {
                    core.iptv.delete(it.id)
                    core.iptvChannels.removeSource(it.id)
                }
                core.bumpSourceVersion()
                renderIptvManage()
                toast("已清理 ${sources.size} 个失效电视源")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmCleanupSmbSources(sources: List<SmbSource>) {
        if (sources.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清理失效 NAS 源")
            .setMessage("确认移除 ${sources.size} 个失效 NAS 片源？")
            .setPositiveButton("移除") { _, _ ->
                sources.forEach {
                    core.sources.delete(it.id)
                    core.smbPool.invalidate(it.id)
                }
                core.bumpSourceVersion()
                renderSmbManage()
                toast("已清理 ${sources.size} 个失效 NAS 片源")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testSource(source: SmbSource) {
        toast("正在检测 ${source.host}…")
        lifecycleScope.launch {
            val result = core.testSource(source)
            result.fold(
                onSuccess = {
                    core.markSmbValid(source.id)
                    toast("检测成功：${source.host}/${source.share}")
                },
                onFailure = {
                    core.markSmbInvalid(source.id, it.message ?: "连接失败")
                    toast("检测失败：${it.message}")
                }
            )
            if (screen == Screen.SMB_MANAGE) renderSmbManage()
            if (screen == Screen.HOME) renderHome()
        }
    }

    private fun testIptv(source: IptvSource) {
        toast("正在检测 ${source.name}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = M3uLoader.load(source.url)
                    val parsed = M3uParser.parse(text, source.url)
                    if (parsed.isEmpty()) {
                        listOf(IptvChannel(source.name, source.url, "直接播放"))
                    } else {
                        parsed
                    }
                }
            }
            result.fold(
                onSuccess = { channels ->
                    val merged = withContext(Dispatchers.IO) {
                        core.iptvChannels.replace(source.id, channels)
                    }
                    core.markIptvValid(source.id)
                    toast("检测成功：${merged.size} 个频道")
                },
                onFailure = {
                    core.markIptvInvalid(source.id, it.message ?: "电视源读取失败")
                    toast("检测失败：${it.message}")
                }
            )
            if (screen == Screen.IPTV_MANAGE) renderIptvManage()
            if (screen == Screen.HOME) renderHome()
        }
    }

    private fun showIptvDialog(existing: IptvSource?) {
        val container = formContainer()
        val nameEdit = addField(container, "名称", existing?.name.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val urlEdit = addField(
            container,
            "M3U / M3U8 地址",
            existing?.url.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加电视源" else "编辑电视源")
            .setView(formScroll(container))
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                val url = urlEdit.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    toast("名称和地址不能为空")
                    return@setOnClickListener
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast("电视源地址必须是 http/https")
                    return@setOnClickListener
                }
                core.iptv.upsert(
                    IptvSource(existing?.id ?: core.iptv.newId(), name, url)
                )
                renderIptvManage()
                renderHome()
                toast("已保存")
                dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(640), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showSourceDialog(existing: SmbSource?) {
        val container = formContainer()
        val nameEdit = addField(container, "名称", existing?.name.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val hostEdit = addField(
            container,
            "主机 / IP",
            existing?.host.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val portEdit = addField(container, "端口", (existing?.port ?: 445).toString(), InputType.TYPE_CLASS_NUMBER)
        val shareEdit = addField(container, "共享名", existing?.share.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val domainEdit = addField(container, "域（可空）", existing?.domain.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val userEdit = addField(container, "用户名（留空按 Guest）", existing?.username.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val passwordEdit = addField(
            container,
            "密码",
            existing?.password.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加 SMB 片源" else "编辑 SMB 片源")
            .setView(formScroll(container))
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                val host = hostEdit.text.toString().trim().removePrefix("smb://").trimEnd('/')
                val share = shareEdit.text.toString().trim().trim('/')
                val port = portEdit.text.toString().trim().toIntOrNull() ?: 445
                if (name.isEmpty() || host.isEmpty() || share.isEmpty()) {
                    toast("名称、主机、共享名不能为空")
                    return@setOnClickListener
                }
                if (port !in 1..65535) {
                    toast("端口必须在 1..65535")
                    return@setOnClickListener
                }
                val source = SmbSource(
                    id = existing?.id ?: core.sources.newId(),
                    name = name,
                    host = host,
                    port = port,
                    share = share,
                    domain = domainEdit.text.toString().trim(),
                    username = userEdit.text.toString().trim(),
                    password = passwordEdit.text.toString()
                )
                core.sources.upsert(source)
                core.smbPool.invalidate(source.id)
                renderSmbManage()
                renderHome()
                toast("已保存")
                dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(640), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun formContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(4), dp(24), dp(4))
    }

    private fun formScroll(container: LinearLayout): ScrollView = ScrollView(this).apply { addView(container) }

    private fun addField(container: LinearLayout, label: String, value: String, inputType: Int): EditText {
        container.addView(
            textView(label, 13f, R.color.text_dim).apply { setPadding(0, dp(8), 0, dp(4)) }
        )
        val edit = EditText(this).apply {
            setText(value)
            this.inputType = inputType
            setBackgroundResource(R.drawable.edit_bg)
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_dim))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 16f
            isSingleLine = true
        }
        container.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return edit
    }

    // ---------------- Browse ----------------

    private fun openBrowse(sourceId: String, path: String) {
        browseSourceId = sourceId
        browsePath = path
        browseEntries = emptyList()
        browseError = null
        browseLoading = true
        showScreen(Screen.BROWSE)
        loadBrowse()
    }

    private fun loadBrowse() {
        val source = browseSourceId?.let { core.sources.get(it) }
        if (source == null) {
            browseLoading = false
            browseError = "片源不存在"
            renderBrowse()
            return
        }
        browseJob?.cancel()
        browseJob = lifecycleScope.launch {
            browseLoading = true
            browseError = null
            renderBrowse()
            val result = withContext(Dispatchers.IO) {
                runCatching { core.smbPool.list(source, browsePath) }
            }
            if (screen != Screen.BROWSE) return@launch
            browseLoading = false
            result.onSuccess {
                browseEntries = it
                core.markSmbValid(source.id)
            }.onFailure {
                browseEntries = emptyList()
                browseError = it.message ?: "目录读取失败"
                core.markSmbInvalid(source.id, browseError!!)
            }
            renderBrowse()
        }
    }

    private fun renderBrowse() {
        browseContent.removeAllViews()
        val source = browseSourceId?.let { core.sources.get(it) }
        pageTitle(browseContent, "浏览片源", "/${browsePath}" + (source?.let { " · ${it.name}" } ?: ""))
        var first: View? = null

        when {
            browseLoading -> addText(browseContent, "加载中…")
            browseError != null -> {
                addText(browseContent, "读取失败：${browseError}")
                first = addRow(browseContent, "重试", null) { loadBrowse() }
            }
            else -> {
                if (browsePath.isNotEmpty()) {
                    first = addRow(browseContent, "‹ 上一级", null) { navigateBrowseUp() }
                }
                if (browseEntries.isEmpty()) {
                    addText(browseContent, "这个目录是空的")
                }
                browseEntries.forEach { entry ->
                    val detail = if (entry.isDirectory) "目录" else formatSize(entry.size)
                    val row = addRow(browseContent, entry.name, detail) {
                        if (entry.isDirectory) {
                            val sourceId = browseSourceId
                            if (sourceId != null) openBrowse(sourceId, entry.path)
                        } else {
                            playBrowseFile(entry)
                        }
                    }
                    if (first == null) first = row
                }
            }
        }
        if (browsePath.isEmpty()) {
            val manage = addRow(browseContent, "管理 NAS 片源", "添加 / 编辑 / 删除共享") {
                showScreen(Screen.SMB_MANAGE)
            }
            if (first == null) first = manage
        }
        focusLater(first)
    }

    private fun navigateBrowseUp() {
        if (browsePath.isEmpty()) {
            goHome()
        } else {
            val sourceId = browseSourceId ?: return goHome()
            openBrowse(sourceId, browsePath.substringBeforeLast('/', ""))
        }
    }

    private fun playBrowseFile(entry: SmbEntry) {
        val sourceId = browseSourceId ?: return
        val args = buildJsonObject {
            put("source_id", sourceId)
            put("path", entry.path)
            put("title", entry.name)
        }
        playMedia(args, returnScreen = Screen.BROWSE)
    }

    // ---------------- TV channels ----------------

    private fun openChannels(sourceId: String) {
        channelSourceId = sourceId
        channels = core.iptvChannels.get(sourceId)
        channelsError = null
        channelsLoading = channels.isEmpty()
        autoPlayFirstChannel = true
        channelPage = 0
        showScreen(Screen.CHANNELS)
        loadChannels()
    }

    private fun loadChannels() {
        val source = channelSourceId?.let { core.iptv.get(it) }
        if (source == null) {
            channelsLoading = false
            channelsError = "电视源不存在"
            renderChannels()
            return
        }
        val autoPlay = autoPlayFirstChannel
        autoPlayFirstChannel = false
        channelPage = 0
        if (channels.isEmpty()) channels = core.iptvChannels.get(source.id)
        channelsLoading = channels.isEmpty()
        channelsError = null
        channelsJob?.cancel()
        channelsJob = lifecycleScope.launch {
            renderChannels()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = M3uLoader.load(source.url)
                    val parsed = M3uParser.parse(text, source.url)
                    if (parsed.isEmpty()) {
                        listOf(IptvChannel(source.name, source.url, "直接播放"))
                    } else {
                        parsed
                    }
                }
            }
            if (screen != Screen.CHANNELS) return@launch
            channelsLoading = false
            val loaded = result.getOrNull()
            if (loaded != null) {
                channels = withContext(Dispatchers.IO) {
                    core.iptvChannels.replace(source.id, loaded)
                }
                channelsError = null
                core.markIptvValid(source.id)
            } else {
                val error = result.exceptionOrNull()?.message ?: "电视源读取失败"
                core.markIptvInvalid(source.id, error)
                if (channels.isEmpty()) {
                    channelsError = error
                } else {
                    toast("电视源失效，使用缓存频道")
                }
            }
            renderChannels()
            if (autoPlay && channels.isNotEmpty() && !menuVisible) {
                playChannel(channels.first())
            }
        }
    }

    private fun renderChannels() {
        channelsContent.removeAllViews()
        val source = channelSourceId?.let { core.iptv.get(it) }
        val subtitle = buildString {
            append(source?.name ?: "")
            if (source?.invalid == true) append(" · 失效，使用缓存")
        }
        pageTitle(channelsContent, "电视直播", subtitle)
        var first: View? = null

        when {
            channelsLoading -> addText(channelsContent, "正在读取电视源…")
            channelsError != null -> {
                addText(channelsContent, "读取失败：${channelsError}")
                first = addRow(channelsContent, "重试", null) { loadChannels() }
            }
            channels.isEmpty() -> addText(channelsContent, "没有解析到频道")
            else -> {
                val pageCount = (channels.size + MAX_CHANNEL_ROWS - 1) / MAX_CHANNEL_ROWS
                channelPage = channelPage.coerceIn(0, pageCount - 1)
                val start = channelPage * MAX_CHANNEL_ROWS
                val end = minOf(start + MAX_CHANNEL_ROWS, channels.size)
                if (pageCount > 1) {
                    if (channelPage > 0) {
                        addRow(
                            channelsContent,
                            "上一页",
                            "当前第 ${channelPage + 1} / $pageCount 页"
                        ) {
                            channelPage -= 1
                            renderChannels()
                        }
                    }
                    if (channelPage < pageCount - 1) {
                        addRow(
                            channelsContent,
                            "下一页",
                            "前往第 ${channelPage + 2} / $pageCount 页"
                        ) {
                            channelPage += 1
                            renderChannels()
                        }
                    }
                }
                var firstChannel: View? = null
                channels.subList(start, end).forEach { channel ->
                    val detail = if (channel.invalid) {
                        "失效：${channel.lastError ?: "播放失败"}"
                    } else {
                        channel.group?.ifBlank { "直播" } ?: "直播"
                    }
                    val row = addRow(channelsContent, channel.name, detail) { playChannel(channel) }
                    if (firstChannel == null) firstChannel = row
                }
                first = firstChannel
            }
        }
        val invalid = channels.count { it.invalid }
        if (invalid > 0) {
            addRow(channelsContent, "清理本源的失效频道", "$invalid 项") {
                val sourceId = channelSourceId ?: return@addRow
                lifecycleScope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        core.iptvChannels.removeInvalid(sourceId)
                    }
                    core.bumpSourceVersion()
                    renderChannels()
                    toast("已移除 $removed 个失效频道")
                }
            }
        }
        val refresh = addRow(channelsContent, "刷新频道", "重新拉取 M3U") { loadChannels() }
        if (first == null) first = refresh
        addRow(channelsContent, "管理电视源", "添加 / 编辑 / 删除") {
            showScreen(Screen.IPTV_MANAGE)
        }
        focusLater(first)
    }

    private fun playChannel(channel: CachedChannel) {
        val args = buildJsonObject {
            put("url", channel.url)
            put("title", channel.name)
        }
        playMedia(args, allowResume = false, returnScreen = Screen.CHANNELS)
    }

    // ---------------- AI list ----------------

    private fun renderAiList() {
        aiListContent.removeAllViews()
        pageTitle(aiListContent, "AI 列表", core.aiList.name())
        val items = core.aiList.items()
        var first: View? = null
        if (items.isEmpty()) {
            addText(aiListContent, "AI 还没有下发列表")
        }
        items.forEach { item ->
            val detail = if (item.invalid) {
                "失效：${item.lastError ?: "播放失败"}"
            } else {
                item.group?.ifBlank { "AI 下发" } ?: "AI 下发"
            }
            val row = addRow(aiListContent, item.title, detail) { playAiItem(item) }
            if (first == null) first = row
        }
        val invalid = items.count { it.invalid }
        if (invalid > 0) {
            addRow(aiListContent, "清理失效 AI 项", "$invalid 项") {
                val removed = core.aiList.removeInvalid()
                core.bumpSourceVersion()
                renderAiList()
                toast("已移除 $removed 项")
            }
        }
        focusLater(first)
    }

    private fun playAiItem(item: AiListItem, returnScreen: Screen = Screen.AI_LIST) {
        if (item.url == null && (item.sourceId == null || item.path == null)) {
            toast("这条 AI 列表项缺少地址")
            return
        }
        val args = buildJsonObject {
            item.url?.let { put("url", it) }
            item.sourceId?.let { put("source_id", it) }
            item.path?.let { put("path", it) }
            put("title", item.title)
        }
        playMedia(args, returnScreen = returnScreen)
    }

    // ---------------- Player ----------------

    private fun applySubtitleStyle() {
        if (!::subtitleOverlay.isInitialized) return
        val style = core.settings.subtitleStyle()
        subtitleOverlay.apply {
            textSize = 26f * style.fontScale.coerceIn(0.5f, 2.0f)
            setTextColor(style.textColor)
            setBackgroundColor(style.backgroundColor)
            if (style.backgroundColor == Color.TRANSPARENT) {
                setShadowLayer(4f, 0f, 2f, Color.BLACK)
            } else {
                setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            val params = layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                params.bottomMargin =
                    (resources.displayMetrics.heightPixels * style.bottomFraction.coerceIn(0f, 0.35f)).toInt()
                layoutParams = params
            }
        }
    }

    private fun renderCue(text: String?) {
        cueText = text
        if (!::subtitleOverlay.isInitialized) return
        if (text.isNullOrBlank()) {
            subtitleOverlay.text = null
            subtitleOverlay.visibility = View.GONE
        } else {
            subtitleOverlay.text = text
            subtitleOverlay.visibility = View.VISIBLE
        }
    }

    private fun playMedia(
        args: kotlinx.serialization.json.JsonObject,
        allowResume: Boolean = true,
        returnScreen: Screen = Screen.HOME
    ) {
        lifecycleScope.launch {
            try {
                val media = MediaSpec.single(core.sources, core.history, args, allowResume = allowResume)
                val plan = MediaFiles.buildPlayPlan(core.sources, core.smbPool, args, media, queueFolder = true)
                playerHidden = false
                playerReturnScreen = returnScreen
                val manager = core.player()
                if (plan.items.size > 1) {
                    manager.playQueue(plan.items, plan.startIndex, plan.startMs)
                } else {
                    manager.play(plan.items[plan.startIndex])
                }
                renderPlayback(manager.snapshot())
            } catch (e: ToolException) {
                toast(e.message ?: "无法播放")
            } catch (e: Exception) {
                toast("无法播放：${e.message}")
            }
        }
    }

    private fun renderPlayback(status: PlaybackStatus?) {
        val previous = lastStatus
        var newMedia = false
        if (status?.hasMedia == true) {
            val newKey = status.mediaKey != lastMediaKey
            val restarted = previous != null &&
                previous.mediaKey == status.mediaKey &&
                previous.positionMs > 10_000 &&
                status.positionMs < 5_000
            if (newKey || restarted) {
                playerHidden = false
                newMedia = newKey
            }
            if (newMedia && menuVisible) closeMenu(resumePlayback = false)
            lastMediaKey = status.mediaKey
        } else {
            lastMediaKey = null
            playerHidden = false
        }
        lastStatus = status

        if (playlistVisible()) {
            if (status?.hasMedia != true) {
                hidePlaylist()
            } else {
                val sig = "${status.mediaKey}|${status.queueIndex}|${status.queueCount}"
                if (sig != lastPlaylistSig) {
                    lastPlaylistSig = sig
                    core.playerOrNull()?.let { renderPlaylist(it) }
                }
            }
        }

        if (status?.error == null) {
            lastErrorShown = null
        } else if (status.error != lastErrorShown) {
            lastErrorShown = status.error
            toast(
                if (looksMissing(status.error)) "播放失败：片源不存在或已被移动"
                else "播放错误：${status.error}"
            )
            markPlaybackFailure(status)
        }

        val show = status?.hasMedia == true && !playerHidden && core.playerOrNull() != null
        if (show) {
            val manager = core.playerOrNull()
            if (manager != null) {
                if (playerView.player !== manager.player) {
                    playerView.player = manager.player
                }
                playerOverlay.visibility = View.VISIBLE
                updateTouchMenuButton()
                val wasShown = overlayShown
                if (!overlayShown) {
                    overlayShown = true
                    playerReturnScreen = screen
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    hideHudNow()
                    if (hasTouchscreen && !touchHintShown) {
                        touchHintShown = true
                        toast("点击画面显示播放控制")
                    }
                    playerView.post {
                        if (playerOverlay.visibility == View.VISIBLE) playerView.requestFocus()
                    }
                }
                if (newMedia && wasShown) {
                    hideHudNow()
                    playerView.requestFocus()
                }
                if (hudVisible) updateHud()
            }
        } else {
            val wasShown = overlayShown
            detachPlayer()
            playerOverlay.visibility = View.GONE
            hideHudNow()
            updateTouchMenuButton()
            if (wasShown) {
                overlayShown = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                when (screen) {
                    Screen.HOME -> renderHome()
                    Screen.BROWSE -> renderBrowse()
                    Screen.CHANNELS -> renderChannels()
                    Screen.AI_LIST -> renderAiList()
                    Screen.PAIRING -> renderPairing()
                    Screen.IPTV_MANAGE -> renderIptvManage()
                    Screen.SMB_MANAGE -> renderSmbManage()
                }
            }
        }

        if (show && status?.state == "ended") {
            val key = status.mediaKey ?: ""
            if (endedHudKey != key) {
                endedHudKey = key
                showHud(HudMode.SEEK, "播放结束 · OK 重播")
            }
        } else if (status?.state != "ended") {
            endedHudKey = null
            if (previous?.state == "ended" && hudVisible) resetHudTimer()
        }

        val signature = "${status?.hasMedia}|${status?.mediaKey}|${status?.state}|${status?.error}"
        if (signature != lastPlaybackSignature) {
            lastPlaybackSignature = signature
            if (screen == Screen.HOME && !menuVisible) renderHome()
        }
    }

    private fun markPlaybackFailure(status: PlaybackStatus) {
        val error = status.error ?: return
        val key = status.mediaKey ?: return
        if (looksMissing(error)) {
            core.history.remove(key)
        }
        if (playerReturnScreen == Screen.CHANNELS && key.startsWith("url:")) {
            val url = key.removePrefix("url:")
            val sourceId = channelSourceId
            if (sourceId != null && channels.any { it.url == url }) {
                core.markChannelInvalid(sourceId, url, error)
            }
        }
        core.markAiItemInvalid(key, error)
    }

    private fun looksMissing(error: String): Boolean {
        val text = error.lowercase()
        return text.contains("status_object_name_not_found") ||
            text.contains("status_object_path_not_found") ||
            text.contains("response code: 404") ||
            text.contains("http 404") ||
            text.contains("no such file")
    }

    private fun showHud(mode: HudMode, hint: String? = null) {
        if (!::playerHud.isInitialized || playerOverlay.visibility != View.VISIBLE) return
        hudMode = mode
        hudHint = hint
        if (!hudVisible) {
            hudVisible = true
            playerHud.visibility = View.VISIBLE
        }
        updateHud()
        resetHudTimer()
    }

    private fun updateHud() {
        if (!hudVisible || !::hudProgress.isInitialized) return
        val status = lastStatus ?: return
        val duration = status.durationMs ?: 0L
        if (::hudPlayPause.isInitialized) {
            hudPlayPause.text = if (status.isPlaying) "暂停" else "播放"
        }
        when (hudMode) {
            HudMode.VOLUME -> {
                hudTitle.text = "音量"
                hudValue.text = if (status.volume <= 0.005f) {
                    "静音"
                } else {
                    "${(status.volume * 100).roundToInt()}%"
                }
                hudProgress.setValues(status.volume)
                hudFooter.visibility = View.GONE
            }
            HudMode.SEEK -> {
                hudTitle.text = status.title ?: "播放"
                hudValue.text = hudHint ?: stateLabel(status)
                val fraction = if (duration > 0) {
                    status.positionMs.toFloat() / duration
                } else {
                    0f
                }
                val buffered = if (duration > 0) {
                    status.bufferedMs.toFloat() / duration
                } else {
                    0f
                }
                hudProgress.setValues(fraction, buffered, showThumb = duration > 0)
                hudTimeLeft.text = formatTime(status.positionMs)
                hudTimeRight.text = if (duration > 0) formatTime(duration) else "直播"
                hudFooter.visibility = View.VISIBLE
            }
        }
    }

    private fun stateLabel(status: PlaybackStatus): String = when (status.state) {
        "playing" -> "播放中"
        "paused" -> "已暂停"
        "buffering" -> "缓冲中"
        "ended" -> "播放结束"
        else -> "播放"
    }

    private fun resetHudTimer() {
        cancelHudHide()
        if (!hudVisible || lastStatus?.state == "ended") return
        val timeout = if (hudMode == HudMode.VOLUME) 1_500L else 3_000L
        val runnable = Runnable { hideHud() }
        hideHudRunnable = runnable
        mainHandler.postDelayed(runnable, timeout)
    }

    private fun cancelHudHide() {
        hideHudRunnable?.let { mainHandler.removeCallbacks(it) }
        hideHudRunnable = null
    }

    private fun hideHud() {
        cancelHudHide()
        if (!hudVisible) return
        hudVisible = false
        hudHint = null
        playerHud.visibility = View.GONE
        if (playerOverlay.visibility == View.VISIBLE) playerView.requestFocus()
    }

    private fun hideHudNow() {
        cancelHudHide()
        hudVisible = false
        hudHint = null
        if (::playerHud.isInitialized) playerHud.visibility = View.GONE
    }

    private fun leavePlayerTo(target: Screen) {
        core.playerOrNull()?.let { manager ->
            if (manager.snapshot().hasMedia) runCatching { manager.pause() }
        }
        playerHidden = true
        hideHudNow()
        hidePlaylist()
        playerOverlay.visibility = View.GONE
        detachPlayer()
        overlayShown = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showScreen(target)
    }

    private fun toggleMenu() {
        if (menuVisible) closeMenu() else openMenu()
    }

    private fun openMenu() {
        if (!::menuOverlay.isInitialized || menuVisible) return
        hideHudNow()
        hidePlaylist()
        focusBeforeMenu = currentFocus
        subtitleMenuVisible = false
        menuResumePlayback = false
        lastHomeBackAt = 0L
        val initial = renderMenu()
        menuVisible = true
        setMenuInset(true)
        menuOverlay.visibility = View.VISIBLE
        updateTouchMenuButton()
        sideMenu.alpha = 0f
        sideMenu.translationX = -dp(24).toFloat()
        sideMenu.animate().alpha(1f).translationX(0f).setDuration(160).start()
        val manager = core.playerOrNull()
        if (playerOverlay.visibility == View.VISIBLE && manager != null) {
            val status = manager.snapshot()
            if (status.hasMedia && status.state != "paused" && status.state != "ended") {
                menuResumePlayback = true
                runCatching { manager.pause() }
            }
        }
        initial.post { if (menuVisible) initial.requestFocus() }
    }

    private fun setMenuInset(open: Boolean) {
        if (!::homeContent.isInitialized) return
        val left = dp(if (open) 360 else 48)
        val bottom = dp(if (hasTouchscreen) 96 else 48)
        val columns = listOf(
            homeContent,
            browseContent,
            channelsContent,
            aiListContent,
            pairingContent,
            iptvManageContent,
            smbManageContent
        )
        columns.forEach { it.setPadding(left, dp(32), dp(48), bottom) }
    }

    private fun addMenuButton(title: String, subtitle: String, onClick: () -> Unit): Button {
        val label = if (subtitle.isBlank()) title else "$title\n$subtitle"
        val spannable = SpannableString(label)
        if (subtitle.isNotBlank()) {
            val subStart = title.length + 1
            spannable.setSpan(
                RelativeSizeSpan(0.78f),
                subStart,
                spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(color(R.color.text_dim)),
                subStart,
                spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val button = Button(this).apply {
            text = spannable
            setBackgroundResource(R.drawable.menu_item_bg)
            setTextColor(color(R.color.text))
            textSize = 16f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            minimumHeight = dp(56)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            isFocusable = true
            isFocusableInTouchMode = true
            setSingleLine(false)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { onClick() }
        }
        menuContent.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        )
        return button
    }

    private fun addTouchMenuButton(title: String, subtitle: String, onClick: () -> Unit) {
        val button = addMenuButton(title, subtitle, onClick)
        button.isFocusable = false
        button.isFocusableInTouchMode = false
    }

    private fun focusFirstMenuButton() {
        val first = (0 until menuContent.childCount)
            .map { menuContent.getChildAt(it) }
            .firstOrNull { it.isFocusable }
            ?: return
        first.post { if (menuVisible) first.requestFocus() }
    }

    private fun renderMenu(): Button {
        menuContent.removeAllViews()
        menuScroll.scrollTo(0, 0)
        subtitleMenuVisible = false
        val buttons = mutableMapOf<MenuAction, Button>()

        fun addItem(title: String, subtitle: String, action: MenuAction): Button {
            val button = addMenuButton(title, subtitle) { onMenuAction(action) }
            buttons[action] = button
            return button
        }

        val iptv = core.iptv.all()
        val smb = core.sources.all()
        val aiItems = core.aiList.items()
        val manager = core.playerOrNull()
        val hasVideo = manager?.snapshot()?.hasMedia == true
        addItem("首页", if (hasVideo) "回到正在播放" else "视频播放", MenuAction.HOME)
        if (hasVideo) {
            val selected = manager?.subtitleTracks()?.firstOrNull { it.selected }
            addMenuButton(
                "字幕",
                selected?.label ?: selected?.language ?: "关闭"
            ) { renderSubtitleMenu() }
        }
        addItem(
            "电视直播",
            if (iptv.isEmpty()) "添加 M3U 电视源" else "${iptv.size} 个电视源",
            MenuAction.LIVE
        )
        addItem(
            "NAS 片源",
            if (smb.isEmpty()) "添加 SMB 共享" else "${smb.size} 个 SMB 片源",
            MenuAction.NAS
        )
        addItem(
            "AI 列表",
            if (aiItems.isEmpty()) "等待 MCP 下发" else "${core.aiList.name()} · ${aiItems.size} 项",
            MenuAction.AI
        )
        addItem("连接 AI", "MCP 扫码 · 手机配片源", MenuAction.PAIRING)
        if (hasTouchscreen) {
            addTouchMenuButton("关闭菜单", "回到当前画面") { closeMenu() }
            addTouchMenuButton("退出应用", "回到桌面") {
                closeMenu(resumePlayback = false)
                exitToDesktop()
            }
        }

        val initialAction = when (screen) {
            Screen.CHANNELS -> MenuAction.LIVE
            Screen.BROWSE -> MenuAction.NAS
            Screen.AI_LIST -> MenuAction.AI
            Screen.PAIRING -> MenuAction.PAIRING
            Screen.IPTV_MANAGE -> MenuAction.LIVE
            Screen.SMB_MANAGE -> MenuAction.NAS
            else -> MenuAction.HOME
        }
        return buttons[initialAction] ?: buttons.getValue(MenuAction.HOME)
    }

    private fun renderSubtitleMenu(files: List<SubtitleFile>? = null, focusStyle: Int? = null) {
        if (!menuVisible) return
        subtitleMenuVisible = true
        menuContent.removeAllViews()
        menuScroll.scrollTo(0, 0)
        menuContent.addView(
            textView("字幕", 20f, R.color.text, bold = true).apply {
                setPadding(dp(16), dp(4), dp(16), dp(8))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val manager = core.playerOrNull()
        val tracks = manager?.subtitleTracks().orEmpty()
        addMenuButton("返回", "回到主菜单") {
            subtitleMenuVisible = false
            renderMenu()
            focusFirstMenuButton()
        }
        addMenuButton(
            "关闭字幕",
            if (tracks.none { it.selected }) "当前已关闭" else "关闭全部字幕"
        ) {
            runCatching { manager?.disableSubtitles() }
            closeMenu()
            toast("已关闭字幕")
        }
        tracks.take(4).forEach { track ->
            val name = track.label ?: track.language ?: "字幕 ${track.index + 1}"
            val detail = buildList {
                if (track.selected) add("当前")
                track.language?.let { add(it) }
                track.mimeType?.let { add(it) }
            }.joinToString(" · ")
            addMenuButton(name, detail.ifBlank { "字幕轨 ${track.index}" }) {
                val result = runCatching { manager?.selectSubtitle(track.index) }
                if (result.isSuccess) {
                    closeMenu()
                    toast("字幕：$name")
                } else {
                    toast("切换字幕失败：${result.exceptionOrNull()?.message}")
                }
            }
        }
        if (files == null) {
            menuContent.addView(
                textView("正在扫描同目录外挂字幕…", 12f, R.color.text_dim).apply {
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            lifecycleScope.launch {
                val discovered = core.siblingSubtitles()
                if (menuVisible && subtitleMenuVisible) renderSubtitleMenu(discovered)
            }
        } else {
            files.take(4).forEach { file ->
                addMenuButton("外挂 · ${file.name}", "加载并显示") {
                    runCatching { core.attachSubtitle(file) }
                        .onSuccess {
                            closeMenu()
                            toast("已加载字幕：${file.name}")
                        }
                        .onFailure { toast("加载字幕失败：${it.message}") }
                }
            }
            if (files.size > 4) {
                menuContent.addView(
                    textView("还有 ${files.size - 4} 个字幕文件，可用 MCP 加载", 12f, R.color.text_dim).apply {
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
        val style = core.settings.subtitleStyle()
        addMenuButton("字号：${(style.fontScale * 100).toInt()}%", "按 OK 切换") {
            cycleSubtitleScale()
            renderSubtitleMenu(files, focusStyle = 0)
        }
        addMenuButton("位置：${(style.bottomFraction * 100).toInt()}%", "按 OK 切换") {
            cycleSubtitleBottom()
            renderSubtitleMenu(files, focusStyle = 1)
        }
        addMenuButton("颜色：${subtitleColorName(style.textColor)}", "按 OK 切换") {
            cycleSubtitleColor()
            renderSubtitleMenu(files, focusStyle = 2)
        }
        val target = focusStyle?.let {
            menuContent.getChildAt(menuContent.childCount - 3 + it)
        }
        if (target != null) {
            target.post { if (menuVisible) target.requestFocus() }
        } else {
            focusFirstMenuButton()
        }
    }

    private fun cycleSubtitleScale() {
        val values = listOf(0.75f, 1.0f, 1.25f, 1.5f)
        val style = core.settings.subtitleStyle()
        val index = values.indexOfFirst { kotlin.math.abs(it - style.fontScale) < 0.01f }
        val next = values[(if (index >= 0) (index + 1) % values.size else 1)]
        core.settings.setSubtitleStyle(style.copy(fontScale = next))
        applySubtitleStyle()
        toast("字幕字号：${(next * 100).toInt()}%")
    }

    private fun cycleSubtitleBottom() {
        val values = listOf(0.03f, 0.08f, 0.14f, 0.22f)
        val style = core.settings.subtitleStyle()
        val index = values.indexOfFirst { kotlin.math.abs(it - style.bottomFraction) < 0.01f }
        val next = values[(if (index >= 0) (index + 1) % values.size else 1)]
        core.settings.setSubtitleStyle(style.copy(bottomFraction = next))
        applySubtitleStyle()
        toast("字幕位置：${(next * 100).toInt()}%")
    }

    private fun cycleSubtitleColor() {
        val values = listOf(
            "白色" to Color.WHITE,
            "黄色" to 0xFFFFEB3B.toInt(),
            "青色" to 0xFF80DEEA.toInt(),
            "绿色" to 0xFFA5D6A7.toInt()
        )
        val style = core.settings.subtitleStyle()
        val index = values.indexOfFirst { it.second == style.textColor }
        val next = values[(if (index >= 0) (index + 1) % values.size else 0)]
        core.settings.setSubtitleStyle(style.copy(textColor = next.second))
        applySubtitleStyle()
        toast("字幕颜色：${next.first}")
    }

    private fun subtitleColorName(color: Int): String = when (color) {
        Color.WHITE -> "白色"
        0xFFFFEB3B.toInt() -> "黄色"
        0xFF80DEEA.toInt() -> "青色"
        0xFFA5D6A7.toInt() -> "绿色"
        else -> "自定义"
    }

    private fun closeMenu(resumePlayback: Boolean = true) {
        if (!menuVisible) return
        menuVisible = false
        subtitleMenuVisible = false
        setMenuInset(false)
        menuOverlay.visibility = View.GONE
        sideMenu.animate().cancel()
        val manager = core.playerOrNull()
        val playerShown = playerOverlay.visibility == View.VISIBLE
        if (resumePlayback && menuResumePlayback && playerShown) {
            menuResumePlayback = false
            runCatching { manager?.resume() }
            playerView.post { playerView.requestFocus() }
        } else {
            val before = focusBeforeMenu
            if (before != null && before.isShown) {
                before.post { before.requestFocus() }
            } else if (playerShown) {
                playerView.post { playerView.requestFocus() }
            } else {
                val fallback = lastFirstFocus
                if (fallback != null && fallback.isShown) fallback.post { fallback.requestFocus() }
            }
        }
        menuResumePlayback = false
        focusBeforeMenu = null
        updateTouchMenuButton()
    }

    private fun onMenuAction(action: MenuAction) {
        if (action == MenuAction.HOME) {
            closeMenu(resumePlayback = false)
            goHome(resume = true)
            return
        }
        if (playerOverlay.visibility == View.VISIBLE) {
            menuResumePlayback = false
            dismissPlayerForNavigation()
        }
        when (action) {
            MenuAction.HOME -> showScreen(Screen.HOME)
            MenuAction.LIVE -> {
                val source = core.iptv.all().firstOrNull()
                if (source == null) showScreen(Screen.IPTV_MANAGE) else openChannels(source.id)
            }
            MenuAction.NAS -> {
                val source = core.sources.all().firstOrNull()
                if (source == null) showScreen(Screen.SMB_MANAGE) else openBrowse(source.id, "")
            }
            MenuAction.AI -> showScreen(Screen.AI_LIST)
            MenuAction.PAIRING -> showScreen(Screen.PAIRING)
        }
    }

    private fun dismissPlayerForNavigation() {
        core.playerOrNull()?.let { manager ->
            if (manager.snapshot().hasMedia) runCatching { manager.pause() }
        }
        playerHidden = true
        hideHudNow()
        hidePlaylist()
        playerOverlay.visibility = View.GONE
        detachPlayer()
        overlayShown = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateTouchMenuButton()
    }

    private fun exitToDesktop() {
        core.playerOrNull()?.let { manager ->
            if (manager.snapshot().hasMedia) runCatching { manager.pause() }
        }
        finishAffinity()
    }

    private fun detachPlayer() {
        if (playerView.player != null) playerView.player = null
    }

    // ---------------- UI helpers ----------------

    private fun pageTitle(content: LinearLayout, title: String, subtitle: String? = null) {
        content.addView(textView(title, 30f, R.color.text, bold = true))
        if (!subtitle.isNullOrBlank()) {
            content.addView(
                textView(subtitle, 14f, R.color.text_dim).apply {
                    setPadding(0, dp(6), 0, dp(18))
                }
            )
        } else {
            content.addView(
                View(this),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18))
            )
        }
    }

    private fun heading(content: LinearLayout, text: String) {
        content.addView(
            textView(text, 18f, R.color.text, bold = true).apply {
                setPadding(0, dp(16), 0, dp(8))
            }
        )
    }

    private fun addText(content: LinearLayout, text: String) {
        content.addView(
            textView(text, 15f, R.color.text_dim).apply {
                setPadding(0, dp(8), 0, dp(8))
            }
        )
    }

    private fun addRow(
        content: LinearLayout,
        title: String,
        subtitle: String?,
        onClick: () -> Unit
    ): Button {
        val row = rowButton(if (subtitle.isNullOrBlank()) title else "$title\n$subtitle")
        row.setOnClickListener { onClick() }
        content.addView(row, rowLayoutParams())
        return row
    }

    private fun rowButton(text: String): Button = Button(this).apply {
        this.text = text
        setBackgroundResource(R.drawable.btn_bg)
        setTextColor(color(R.color.text))
        textSize = 17f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = dp(72)
        minimumHeight = dp(72)
        setPadding(dp(24), dp(10), dp(24), dp(10))
        isFocusable = true
        isFocusableInTouchMode = true
        setSingleLine(false)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun textView(text: String, size: Float, colorId: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color(colorId))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rowLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(8) }

    private fun matchParent(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun focusLater(view: View?) {
        val target = view ?: return
        lastFirstFocus = target
        target.post {
            if (!menuVisible && playerOverlay.visibility != View.VISIBLE && target.isShown) {
                target.requestFocus()
            }
        }
    }

    private fun hasUsableFocus(): Boolean {
        val focus = currentFocus ?: return false
        return focus.isShown
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playerOverlay.visibility != View.VISIBLE && currentFocus == null) {
            val target = lastFirstFocus
            if (target != null && target.isShown) target.post { target.requestFocus() }
        }
    }

    // ---------------- QR / misc ----------------

    private fun makeQr(content: String): Bitmap {
        if (content == qrBitmapUrl && qrBitmap != null) return qrBitmap!!
        val size = 600
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        qrBitmap = bitmap
        qrBitmapUrl = content
        return bitmap
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("MCP URL", url))
        toast("MCP 地址已复制")
    }

    private fun startServerService() {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, McpServerService::class.java))
        }.onFailure { toast("无法启动 MCP 服务：${it.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble() / 1024
        val units = arrayOf("KB", "MB", "GB", "TB")
        var unit = units[0]
        for (i in units.indices) {
            unit = units[i]
            if (value < 1024 || i == units.lastIndex) break
            value /= 1024
        }
        return String.format(Locale.US, "%.1f %s", value, unit)
    }

    companion object {
        const val EXTRA_SHOW_PLAYER = "show_player"
        private const val MAX_CHANNEL_ROWS = 400
        private const val SEEK_STEP_MS = 10_000L
        private const val VOLUME_STEP = 0.01f
        private const val SEEK_REPEAT_MS = 250L
        private const val VOLUME_REPEAT_MS = 90L

        private val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU
        )

        @Volatile
        var isVisible = false
            private set
    }
}
