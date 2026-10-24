package dev.ebookd.reader.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ebookd.core.net.BookDetailDto
import dev.ebookd.reader.R
import dev.ebookd.reader.ui.common.BookCover
import kotlin.math.roundToInt

/**
 * One book: metadata, its files, and the reading entry point.
 *
 * The screen keeps the cached row on display while the live fetch is in flight or has failed —
 * the reader came here from the shelf and already knows which book this is, so the honest states
 * are "the details are still loading" and "the details could not be loaded", not a blank page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onRead: (String) -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cached by viewModel.cached.collectAsStateWithLifecycle()
    val percent by viewModel.percent.collectAsStateWithLifecycle()
    val baseUrl by viewModel.coverBase.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val hasLocalCover by viewModel.hasLocalCover.collectAsStateWithLifecycle()

    // The photo picker rather than `GetContent`: a cover comes from the camera roll as often as from
    // a file manager, and the picker needs no storage permission to do it.
    val pickCover = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) viewModel.setCover(uri) },
    )

    val title = state.detail?.book?.title?.takeIf { it.isNotBlank() } ?: cached?.title.orEmpty()
    // Authors, series and cover come from the cached row: the detail endpoint returns a different
    // shape than the list (snake_case, contributors as their own resource) and does not carry them.
    val authors = cached?.authors.orEmpty()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "书籍" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(
                    bookId = viewModel.bookId,
                    title = title,
                    coverUrl = cached?.coverUrl,
                    baseUrl = baseUrl,
                    localFile = viewModel.localCover,
                    modifier = Modifier
                        .width(110.dp)
                        .height(165.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (authors.isNotBlank()) Text(authors, style = MaterialTheme.typography.bodyMedium)
                    cached?.seriesName?.let { series ->
                        Text(
                            buildString {
                                append(series)
                                state.detail?.book?.seriesIndex?.let { append(" · 第 ${it.toInt()} 册") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.detail?.book?.year?.let { Text("$it 年", style = MaterialTheme.typography.bodySmall) }
                    state.detail?.book?.language?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    // This device's own row first, the cached library row only when there is none:
                    // the library's copy is whatever the server last knew, and the reader's device
                    // is the authority on where the reader is.
                    (percent ?: cached?.percent)?.takeIf { it > 0 }?.let { read ->
                        // The stored number is 0..100 — the server's unit and the reader's own row's;
                        // scaling it again made a book read to 4 % display as 400 %.
                        Text("已读 ${read.roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (state.loading && state.detail == null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error?.let { message ->
                Column {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text("重试") }
                }
            }

            state.detail?.let { detail -> DetailBody(detail) }

            // One action, not two: a book that is not on the phone yet is fetched by the reader that
            // the tap opens, which is what the tap already meant.
            //
            // Enabled by *format*, and from either source. The cached row answers it with the server
            // unreachable, and gating on the live detail alone greyed this out for a book that was
            // sitting on the device ready to read.
            val hasTxt = state.detail?.files?.any { it.format.equals("txt", ignoreCase = true) }
                ?: cached?.formats?.split(',')?.any { it.trim().equals("txt", ignoreCase = true) }
                ?: false
            Button(onClick = { onRead(viewModel.bookId) }, enabled = hasTxt) { Text("开始阅读") }

            // A cover the reader chose is theirs alone: the server keeps it in the private layer, so
            // the library's own record and every other reader's shelf are untouched by this row.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        pickCover.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !busy,
                ) { Text(if (busy) "处理中…" else "自定义封面") }
                if (hasLocalCover) {
                    TextButton(onClick = viewModel::clearCover, enabled = !busy) { Text("恢复默认封面") }
                }
            }

        }
    }
}

@Composable
private fun DetailBody(detail: BookDetailDto) {
    val book = detail.book
    if (book.needsReview) {
        Text(
            "标题是从文件名推断的，可能不准确。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    book.description?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    if (detail.files.isNotEmpty()) {
        Text("文件", style = MaterialTheme.typography.titleSmall)
        detail.files.forEach { file ->
            Text(
                buildString {
                    append(file.format.uppercase())
                    append(" · ")
                    append(formatSize(file.sizeBytes))
                    file.charset?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (book.tags.isNotEmpty()) {
        Text("标签：${book.tags.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
