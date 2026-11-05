package com.dev.Tvivo.download

/** Pure preflight shared by movie, episode, and season queue actions. */
object DownloadPlanner {
    data class Item(val bytes: Long?, val exact: Boolean, val alreadyQueuedOrDone: Boolean = false)
    sealed interface Plan {
        data object Ready : Plan
        data class NeedsConfirmation(val projected: Long, val cap: Long, val overBy: Long, val itemCount: Int) : Plan
        data class Blocked(val unknownSize: Int) : Plan
        data object AlreadyQueuedOrDone : Plan
    }

    fun plan(items: List<Item>, cap: Long, used: Long, reserved: Long): Plan {
        require(items.isNotEmpty())
        if (items.all { it.alreadyQueuedOrDone }) return Plan.AlreadyQueuedOrDone
        val pending = items.filterNot { it.alreadyQueuedOrDone }
        val unknown = pending.count { it.bytes == null || it.bytes < 0 }
        if (unknown > 0) return Plan.Blocked(unknown)
        return when (val evaluation = DownloadQuota.evaluate(
            pending.map { DownloadQuota.ItemSize(it.bytes, it.exact) }, cap, used, reserved
        )) {
            is DownloadQuota.Evaluation.Fits -> Plan.Ready
            is DownloadQuota.Evaluation.ExceedsCap -> Plan.NeedsConfirmation(
                evaluation.projected, evaluation.cap, evaluation.overBy, pending.size
            )
            DownloadQuota.Evaluation.SizeUnknown -> Plan.Blocked(pending.size)
        }
    }
}
