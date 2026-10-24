package dev.ebookd.reader.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItemsOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.ebookd.core.db.BookEntity
import dev.ebookd.core.net.TagDto
import dev.ebookd.reader.R
import dev.ebookd.reader.ui.common.BookCover
import java.io.File
import kotlinx.coroutines.delay

/**
 * The shelf: a grid of what the reader has, rendered from the local cache and refreshed behind it.
 *
 * Every failure state here is explicit — loading, "the refresh failed but here is what we have",
 * empty — because the alternative (an empty grid) is indistinguishable from a library that is
 * actually empty, which is the most alarming thing a reader can be shown.
 *
 * Long-pressing a cover opens selection mode, and the bar at the bottom then files the selection:
 * mark it read, tag it, or — with the capability for it — remove it from the server. The actions
 * that write shared metadata are drawn only when the server has said this session may, so the menu
 * is a list of what the reader can do rather than a list of ways to be refused.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    onOpenBook: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    viewModel: ShelfViewModel = hiltViewModel(),
) {
    val books = viewModel.books.collectAsLazyPagingItems()
    val percents by viewModel.percents.collectAsStateWithLifecycle()
    val baseUrl by viewModel.coverBase.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val selecting by viewModel.selecting.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pendingStatus by viewModel.pendingStatus.collectAsStateWithLifecycle()
    val localCovers by viewModel.localCovers.collectAsStateWithLifecycle()

    /** The rows paged in so far, which is everything a selection can currently reach. */
    val loaded = books.itemSnapshotList.items

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    var tagging by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var choosingStatus by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitSelection() }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "退出多选")
                        }
                    },
                    title = { Text("已选 ${selection.size} 本") },
                    actions = {
                        // The count is what 全选 is about to select — the rows already paged in — not
                        // the size of the library, which the shelf does not know without a query.
                        TextButton(onClick = { viewModel.selectAll(loaded.map { it.id }) }) {
                            Text("全选 ${loaded.size}")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("书架") },
                    actions = {
                        IconButton(onClick = onOpenDownloads) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = "下载")
                        }
                        IconButton(onClick = onOpenLibrary) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = "书库")
                        }
                        // Last, and honest about itself: the icon carries the pending count, so a reader
                        // whose train just left the tunnel can see that the app knows what it still owes.
                        BadgedBox(badge = { pendingBadge(pending) }) {
                            IconButton(onClick = onOpenAccount) {
                                Icon(painterResource(R.drawable.ic_sync), contentDescription = "同步与账户")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selecting) {
                BatchBar(
                    enabled = selection.isNotEmpty() && !busy,
                    busy = busy,
                    canEditShared = "library:edit" in capabilities,
                    canDelete = "batch:delete" in capabilities,
                    onMarkAs = { choosingStatus = true },
                    onTag = { tagging = true },
                    onDelete = { confirmingDelete = true },
                )
            }
        },
    ) { padding ->
        val refresh = books.loadState.refresh
        when {
            refresh is LoadState.Loading && books.itemCount == 0 -> CenteredBox { CircularProgressIndicator() }

            refresh is LoadState.Error && books.itemCount == 0 -> CenteredBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("书架刷新失败")
                    Text(
                        "无法连接服务器。已下载的书仍会保留在本地。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { books.retry() }) { Text("重试") }
                }
            }

            books.itemCount == 0 -> CenteredBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("书架是空的")
                    Text("在这个服务器上还没有书。", style = MaterialTheme.typography.bodySmall)
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(books.itemCount, key = books.itemKey { it.id }) { index ->
                    books[index]?.let { book ->
                        val status = pendingStatus[book.id] ?: book.status
                        BookCard(
                            book = book,
                            percent = percents[book.id] ?: book.percent,
                            baseUrl = baseUrl,
                            localFile = if (book.id in localCovers) viewModel.coverFile(book.id) else null,
                            status = ReadingStatus.badge(status),
                            selected = book.id in selection,
                            selecting = selecting,
                            onClick = {
                                if (selecting) viewModel.toggle(book.id) else onOpenBook(book.id)
                            },
                            onLongClick = {
                                viewModel.enterSelection()
                                viewModel.toggle(book.id)
                            },
                        )
                    }
                }
                if (books.loadState.append is LoadState.Error) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = { books.retry() }) { Text("加载下一页失败，点击重试") }
                    }
                }
            }
        }
    }

    if (choosingStatus) {
        StatusDialog(
            onDismiss = { choosingStatus = false },
            onPick = { code ->
                choosingStatus = false
                viewModel.markStatus(code)
            },
        )
    }

    if (tagging) {
        TagDialog(
            selected = selection.size,
            onDismiss = { tagging = false },
            onConfirm = { names ->
                tagging = false
                viewModel.addTags(names)
            },
            suggestions = { query -> viewModel.tags(query) },
        )
    }

    if (confirmingDelete) {
        val count = selection.size
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("从服务器移除 $count 本？") },
            text = {
                Text("书目会从服务器上消失，其他设备上也会跟着不见。已经下载到这台手机的文件暂时还留着，可以离线读完。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.removeBooks()
                }) { Text("确认移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } },
        )
    }
}

/**
 * The filing actions, along the bottom where a thumb is.
 *
 * Text rather than glyphs: 标为/加标签/移除 say what they do, and the three icons this screen would
 * otherwise need are three paths nobody can verify by reading them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchBar(
    enabled: Boolean,
    busy: Boolean,
    canEditShared: Boolean,
    canDelete: Boolean,
    onMarkAs: () -> Unit,
    onTag: () -> Unit,
    onDelete: () -> Unit,
) {
    // The inset is the whole point of this line: a bare Surface in `bottomBar` draws its buttons
    // under the system navigation bar, where they are visible, untappable, and look disabled because
    // the nav scrim is lying over them. Material's own bars add this padding; a custom one has to.
    Surface(modifier = Modifier.windowInsetsPadding(NavigationBarDefaults.windowInsets), tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = enabled && !busy, onClick = onMarkAs) { Text("标为") }
            if (canEditShared) {
                TextButton(enabled = enabled && !busy, onClick = onTag) { Text("加标签") }
            }
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            if (canDelete) {
                TextButton(enabled = enabled && !busy, onClick = onDelete) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * The status choices, as a dialog rather than a menu hanging off the bar.
 *
 * A `DropdownMenu` anchored to a bottom bar opens downward, and there is no downward: the menu is
 * either clipped or lands under the navigation bar. A dialog is the same number of taps and is
 * always on screen.
 */
@Composable
private fun StatusDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标为") },
        text = {
            Column {
                ReadingStatus.menu.forEach { (code, label) ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPick(code) },
                    ) { Text(label) }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    percent: Double?,
    baseUrl: String?,
    localFile: File?,
    status: String?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            BookCover(
                bookId = book.id,
                title = book.title,
                coverUrl = book.coverUrl,
                baseUrl = baseUrl,
                localFile = localFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.small),
            )
            if (selecting) {
                // Display only: the card itself is the toggle, so a tap anywhere on the cover reads
                // as a tap on the card rather than as a miss that deselects nothing.
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .scale(0.8f),
                )
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (book.authors.isNotBlank()) {
            Text(
                text = book.authors,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        percent?.takeIf { it > 0.0 }?.let { value ->
            // `/100`, which is the bug this line used to be: the column holds 0..100 — the server's
            // unit and the reader's own — and a 0..1 indicator fed with 3.5 saturates at full for
            // every book past the third page, so the whole shelf looked finished.
            LinearProgressIndicator(
                progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun pendingBadge(pending: Int) {
    if (pending <= 0) return
    Badge { Text(pending.toString()) }
}

/**
 * Tagging: pick from what the library already uses, or type a name and create it.
 *
 * The suggestions come from the server rather than from a local table, because the app caches no
 * taxonomy at all — and creating a tag near-identical to one that exists is the failure this picker
 * exists to prevent, so it is worth the round trip. [delay] before the query is a debounce: the
 * effect restarts on every keystroke, so typing five characters costs one request instead of five.
 */
@Composable
private fun TagDialog(
    selected: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    suggestions: suspend (String) -> List<TagDto>,
) {
    var query by remember { mutableStateOf("") }
    var known by remember { mutableStateOf<List<TagDto>>(emptyList()) }
    var chosen by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        delay(200)
        known = suggestions(query)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索或输入新标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chosen.isNotEmpty()) {
                    Text("将加上：${chosen.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                }
                if (query.isNotBlank() && known.none { it.name == query.trim() }) {
                    TextButton(
                        onClick = {
                            chosen = chosen + query.trim()
                            query = ""
                        },
                    ) { Text("新建「${query.trim()}」") }
                }
                if (loading) {
                    Text("正在取现有标签…", style = MaterialTheme.typography.bodySmall)
                } else if (known.isEmpty()) {
                    Text("没有可复用的标签，直接输入名字新建。", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        lazyItemsOf(known, key = { it.id }) { tag ->
                            FilterChip(
                                selected = tag.name in chosen,
                                onClick = {
                                    chosen = if (tag.name in chosen) chosen - tag.name else chosen + tag.name
                                },
                                label = { Text("${tag.name} · ${tag.usageCount}") },
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .width(180.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty() && selected > 0, onClick = { onConfirm(chosen) }) {
                Text("加到 $selected 本")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
