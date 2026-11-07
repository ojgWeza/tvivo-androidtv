package com.dev.Tvivo.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNotificationPermissionPolicyTest {
    @Test fun asksOnceForPhoneDownloadOnlyOnAndroid13WhenNotGranted() {
        assertEquals(true, DownloadNotificationPermissionPolicy.shouldAsk(33, false, false, false))
        assertEquals(false, DownloadNotificationPermissionPolicy.shouldAsk(32, false, false, false))
        assertEquals(false, DownloadNotificationPermissionPolicy.shouldAsk(34, true, false, false))
        assertEquals(false, DownloadNotificationPermissionPolicy.shouldAsk(34, false, true, false))
        assertEquals(false, DownloadNotificationPermissionPolicy.shouldAsk(34, false, false, true))
    }
}
