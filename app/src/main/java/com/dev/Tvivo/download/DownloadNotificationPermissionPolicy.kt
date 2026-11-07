package com.dev.Tvivo.download

internal object DownloadNotificationPermissionPolicy {
    fun shouldAsk(apiLevel: Int, isTelevision: Boolean, granted: Boolean, requestedBefore: Boolean): Boolean =
        apiLevel >= 33 && !isTelevision && !granted && !requestedBefore
}
