package dev.ebookd.reader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ebookd.core.db.DownloadTaskEntity
import dev.ebookd.reader.R

/**
 * The offline list, with the actions the reader actually needs: stop a transfer, try a failed one
 * again, or drop a download while keeping the book in the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onOpenBook: (String) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("下载") }) },
    ) { padding ->
        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有离线书籍")
                Text("在书籍详情里点「开始阅读」，没下载的书会自动开始下载。", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.fileId }) { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(label(task.state))
                                    append(" · ")
                                    append(task.format.uppercase())
                                    append(" · ")
                                    append(size(task.sizeBytes))
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            task.error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        when (task.state) {
                            DownloadTaskEntity.QUEUED, DownloadTaskEntity.RUNNING ->
                                IconButton(onClick = { viewModel.cancel(task.fileId) }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "取消")
                                }

                            DownloadTaskEntity.FAILED ->
                                IconButton(onClick = { viewModel.retry(task.fileId) }) {
                                    Icon(painterResource(R.drawable.ic_retry), contentDescription = "重试")
                                }

                            DownloadTaskEntity.DONE ->
                                IconButton(onClick = { viewModel.delete(task.fileId) }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "删除下载")
                                }

                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

private fun label(state: String) = when (state) {
    DownloadTaskEntity.QUEUED -> "排队中"
    DownloadTaskEntity.RUNNING -> "下载中"
    DownloadTaskEntity.DONE -> "已离线"
    DownloadTaskEntity.FAILED -> "失败"
    DownloadTaskEntity.CANCELLED -> "已取消"
    else -> state
}

private fun size(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.0f KB".format(bytes / 1024.0)
}
