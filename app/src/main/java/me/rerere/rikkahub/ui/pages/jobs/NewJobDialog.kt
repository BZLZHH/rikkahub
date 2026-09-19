package me.rerere.rikkahub.ui.pages.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.job.WorkspaceJobMode

/** 用户手动新建一个后台任务（不经过 AI）。 */
@Composable
fun NewJobDialog(
    onDismiss: () -> Unit,
    onCreate: (command: String, reason: String?, mode: WorkspaceJobMode, cwd: String) -> Unit,
) {
    var command by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(WorkspaceJobMode.PIPE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jobs_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.jobs_new_command)) },
                    placeholder = { Text("npm run build") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.jobs_new_reason)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text(stringResource(R.string.jobs_new_cwd)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == WorkspaceJobMode.PIPE,
                        onClick = { mode = WorkspaceJobMode.PIPE },
                        label = { Text(stringResource(R.string.job_mode_pipe)) },
                    )
                    FilterChip(
                        selected = mode == WorkspaceJobMode.PTY,
                        onClick = { mode = WorkspaceJobMode.PTY },
                        label = { Text(stringResource(R.string.job_mode_pty)) },
                    )
                }
                Text(
                    text = stringResource(R.string.jobs_new_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = command.isNotBlank(),
                onClick = { onCreate(command.trim(), reason.trim().takeIf { it.isNotEmpty() }, mode, cwd.trim()) },
            ) { Text(stringResource(R.string.jobs_new_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
