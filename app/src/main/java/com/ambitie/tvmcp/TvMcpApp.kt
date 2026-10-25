package com.ambitie.tvmcp

import android.app.Application
import com.ambitie.tvmcp.core.TvMcpCore

class TvMcpApp : Application() {
    lateinit var core: TvMcpCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = TvMcpCore(this)
    }
}
