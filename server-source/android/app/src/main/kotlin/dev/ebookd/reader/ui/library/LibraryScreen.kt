package dev.ebookd.reader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.ebookd.core.db.BookEntity
import dev.ebookd.reader.R
import dev.ebookd.reader.ui.common.BookCover

/**
 * The catalogue: the cached list while the search box is empty, server-side search when it is not.
 *
 * Both modes are the same list visually, because they answer the same question — "which book do I
 * want" — and switching the layout between them would make the search feel like a different app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coverBase by viewModel.coverBase.collectAsStateWithLifecycle()
    val localCovers by viewModel.localCovers.collectAsStateWithLifecycle()
    val catalogue = viewModel.catalogue.collectAsLazyPagingItems()

    // Reading the collected set here is what makes the list redraw when a cover changes elsewhere;
    // a lambda that reached into the repository directly would not be observed at all.
    val localFileFor: (String) -> File? = { id -> if (id in localCovers) viewModel.coverFile(id) else null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("搜索书名或作者") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )

            when {
                query.isBlank() -> Catalogue(catalogue, coverBase, localFileFor, onOpenBook)

                state.searching -> Centered { CircularProgressIndicator() }

                state.error != null -> Centered { Text(state.error!!) }

                state.results.isEmpty() -> Centered { Text("没有匹配的书") }

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.results, key = { it.id }) { book ->
                        BookRow(
                            id = book.id,
                            title = book.title,
                            authors = book.authors.joinToString("、"),
                            formats = book.formats.joinToString(" "),
                            coverUrl = book.coverUrl,
                            baseUrl = coverBase,
                            localFile = localFileFor(book.id),
                            onClick = { onOpenBook(book.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Catalogue(
    catalogue: LazyPagingItems<BookEntity>,
    coverBase: String?,
    localFileFor: (String) -> File?,
    onOpenBook: (String) -> Unit,
) {
    val refresh = catalogue.loadState.refresh
    when {
        refresh is LoadState.Loading && catalogue.itemCount == 0 -> Centered { CircularProgressIndicator() }
        refresh is LoadState.Error && catalogue.itemCount == 0 -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("无法读取书库")
                androidx.compose.material3.TextButton(onClick = { catalogue.retry() }) { Text("重试") }
            }
        }
        catalogue.itemCount == 0 -> Centered { Text("书库里还没有书") }
        else -> LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(catalogue.itemCount, key = catalogue.itemKey { it.id }) { index ->
                catalogue[index]?.let { book ->
                    BookRow(
                        id = book.id,
                        title = book.title,
                        authors = book.authors,
                        formats = book.formats,
                        coverUrl = book.coverUrl,
                        baseUrl = coverBase,
                        localFile = localFileFor(book.id),
                        onClick = { onOpenBook(book.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    id: String,
    title: String,
    authors: String,
    formats: String,
    coverUrl: String?,
    baseUrl: String?,
    localFile: File?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookCover(
            bookId = id,
            title = title,
            coverUrl = coverUrl,
            baseUrl = baseUrl,
            localFile = localFile,
            modifier = Modifier
                .width(56.dp)
                .height(84.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (authors.isNotBlank()) {
                Text(authors, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (formats.isNotBlank()) {
                Text(formats, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
