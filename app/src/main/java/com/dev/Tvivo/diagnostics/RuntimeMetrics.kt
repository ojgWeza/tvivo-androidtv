package com.dev.Tvivo.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process

/** A point-in-time process-health sample for an explicit Diagnostics visit. */
object RuntimeMetrics {

    data class Snapshot(
        val javaUsedBytes: Long,
        val javaCommittedBytes: Long,
        val javaMaxBytes: Long,
        val nativeHeapBytes: Long,
        val totalPssBytes: Long,
        val privateDirtyBytes: Long,
        val gcCount: String?,
        val gcTimeMs: String?
    )

    fun collect(context: Context): Snapshot {
        val runtime = Runtime.getRuntime()
        val memory = context.getSystemService(ActivityManager::class.java)
            ?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            ?.firstOrNull()
        return Snapshot(
            javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaCommittedBytes = runtime.totalMemory(),
            javaMaxBytes = runtime.maxMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            totalPssBytes = (memory?.totalPss?.toLong() ?: 0L) * 1024L,
            privateDirtyBytes = (memory?.totalPrivateDirty?.toLong() ?: 0L) * 1024L,
            gcCount = Debug.getRuntimeStat("art.gc.gc-count"),
            gcTimeMs = Debug.getRuntimeStat("art.gc.gc-time")
        )
    }
}
