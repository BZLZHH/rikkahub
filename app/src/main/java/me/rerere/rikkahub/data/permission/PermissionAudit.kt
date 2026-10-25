package me.rerere.rikkahub.data.permission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionAuditEntry(
    val id: Long,
    val tool: String,
    val summary: String,
    /** allow / ask / deny */
    val decision: String,
    val basis: String,
    val ruleId: String?,
    val mode: PermissionMode,
    val channel: PermissionChannel,
    val at: Long,
)

/**
 * 最近的裁决账本（内存环形缓冲）。
 *
 * P1 会落到数据库；先保证"谁批的、凭什么"当场可见，不再只在 logcat 里。
 */
object PermissionAudit {
    private const val CAPACITY = 200
    private val _entries = MutableStateFlow<List<PermissionAuditEntry>>(emptyList())
    val entries: StateFlow<List<PermissionAuditEntry>> = _entries.asStateFlow()

    private var nextId = 1L

    fun record(
        call: NormalizedCall,
        context: PermissionContext,
        decision: String,
        basis: String,
        ruleId: String?,
    ) {
        val entry = PermissionAuditEntry(
            id = nextId++,
            tool = call.tool,
            summary = call.summary,
            decision = decision,
            basis = basis,
            ruleId = ruleId,
            mode = context.mode,
            channel = context.channel,
            at = System.currentTimeMillis(),
        )
        _entries.value = (_entries.value + entry).takeLast(CAPACITY)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
