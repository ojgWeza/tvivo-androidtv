package com.dev.Tvivo.home

/** A directional focus link is valid only while its destination has a rendered node. */
object FocusLinkPolicy {
    fun targetIfRendered(targetKey: String?, renderedKeys: Set<String>): String? =
        targetKey?.takeIf(renderedKeys::contains)

    /** Returns a requester only while its node is composed; otherwise Compose searches normally. */
    fun <T : Any> targetIfRendered(target: T?, isRendered: Boolean): T? =
        target?.takeIf { isRendered }
}
