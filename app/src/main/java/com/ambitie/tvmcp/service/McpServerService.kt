package com.ambitie.tvmcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ambitie.tvmcp.R
import com.ambitie.tvmcp.TvMcpApp
import com.ambitie.tvmcp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class McpServerService : Service() {

    private val core get() = (application as TvMcpApp).core
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotificationSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        core.startServer()

        scope.launch {
            core.serverState.collect { updateNotification() }
        }
        scope.launch {
            core.playbackState.collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> core.playerOrNull()?.toggle()
        }
        if (!core.serverState.value.running) {
            core.startServer()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        core.shutdown()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "电视 MCP 服务与播放控制"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val server = core.serverState.value
        val playback = core.playbackState.value
        val hasMedia = playback?.hasMedia == true

        val title = when {
            hasMedia -> "TV MCP · ${playback?.title ?: "播放中"}"
            else -> "TV MCP 播放器"
        }
        val text = when {
            playback?.isPlaying == true -> "正在播放"
            hasMedia -> "已暂停"
            server.running -> "MCP 服务运行中 · 端口 ${server.port}"
            server.error != null -> "服务启动失败：${server.error}"
            else -> "服务未启动"
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_SHOW_PLAYER, hasMedia)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (hasMedia) {
            val toggle = PendingIntent.getService(
                this,
                1,
                Intent(this, McpServerService::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_tv,
                if (playback?.isPlaying == true) "暂停" else "播放",
                toggle
            )
        }

        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, McpServerService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_tv, "停止服务", stop)
        return builder.build()
    }

    private fun updateNotification() {
        val server = core.serverState.value
        val playback = core.playbackState.value
        val signature = buildString {
            append(server.running)
            append('|').append(server.port)
            append('|').append(server.error)
            append('|').append(playback?.hasMedia)
            append('|').append(playback?.isPlaying)
            append('|').append(playback?.title)
            append('|').append(playback?.error)
        }
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        const val CHANNEL_ID = "tv_mcp_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE = "com.ambitie.tvmcp.TOGGLE"
        const val ACTION_STOP_SERVICE = "com.ambitie.tvmcp.STOP_SERVICE"
    }
}
