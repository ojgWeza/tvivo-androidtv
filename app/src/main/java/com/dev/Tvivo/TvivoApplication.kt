package com.dev.Tvivo

import android.app.Application
import androidx.work.Configuration
import com.dev.Tvivo.diagnostics.CrashDiagnostics
import com.dev.Tvivo.diagnostics.DiagnosticLog

class TvivoApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        // AndroidX Startup's WorkManager initializer is disabled in the manifest. The
        // Configuration.Provider contract initializes it lazily when MainActivity
        // schedules work, after this crash handler is installed.
        CrashDiagnostics.install(this)
    }
}
