package dev.ebookd.reader.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ebookd.core.db.SyncStateEntity
import java.text.DateFormat
import java.util.Date

/**
 * The server this device is talking to, and the state of the conversation with it.
 *
 * Every line here is a number the app actually has: the address it was configured with, when the
 * last exchange finished, how many books still owe a position. Nothing is asserted — "已同步" with
 * nothing behind it is worse than "还没有同步过", because the reader would go on believing their
 * progress was safe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(viewModel: AccountViewModel = hiltViewModel()) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("同步") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Field(label = "服务器", value = baseUrl ?: "未配置")
            Field(label = "上次同步", value = lastSync(server))
            Field(
                label = "待同步",
                value = if (pending == 0) "没有待同步的变化" else "$pending 本书的变化还没上传",
            )
            server?.lastError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.syncNow() }, enabled = !syncing) {
                    Text(if (syncing) "同步中…" else "立即同步")
                }
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                "阅读进度和书架上的标记会自动同步：联网时打开应用、翻页后、改过状态、以及每次下载完成之后。" +
                    "离线时照常记在这台手机上，回到网络后自动补上。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Wall clock, in the device's own format.
 *
 * A relative "3 分钟前" would read as stale the moment it was drawn — the string is fixed while the
 * clock is not — and a reader can subtract two dates but not a frozen "刚刚".
 */
private fun lastSync(state: SyncStateEntity?): String {
    val at = state?.lastSyncAt ?: return "还没有同步过"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
}
