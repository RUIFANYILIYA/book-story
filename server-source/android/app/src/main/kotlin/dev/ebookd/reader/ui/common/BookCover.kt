package dev.ebookd.reader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.ebookd.core.design.absoluteCoverUrl
import java.io.File

/**
 * A cover, or a deterministic stand-in when there is none — and when the bytes arrive but no
 * registered decoder can paint them, which is the same dead end for the reader and has to end the
 * same way.
 *
 * The stand-in is not a grey box: it carries the book's first character on a colour derived from
 * its id, so a shelf of cover-less TXT files is still navigable by eye. The colour derivation has
 * to be stable across launches, or the shelf would rearrange itself in the reader's memory.
 *
 * [localFile] wins over [coverUrl] because it is the reader's own choice for this device, and
 * because the server's cover URL does not change when an override is written behind it — a client
 * that kept asking the same URL would keep getting the same cached answer and conclude the new
 * cover had not arrived.
 */
@Composable
fun BookCover(
    bookId: String,
    title: String,
    coverUrl: String?,
    baseUrl: String?,
    localFile: File? = null,
    modifier: Modifier = Modifier,
) {
    val absolute = absoluteCoverUrl(baseUrl, coverUrl)
    val local = localFile?.takeIf { it.exists() }
    val model: Any? = local ?: absolute
    var failed by remember(model) { mutableStateOf(false) }
    if (model != null && !failed) {
        AsyncImage(
            model = if (local != null) {
                // The path alone would be the cache key, and a replaced cover keeps the same path —
                // so the second cover a reader picks would draw the first one's bytes from memory.
                // The file's own stamp is the cheapest thing that changes when its content does.
                ImageRequest.Builder(LocalContext.current)
                    .data(local)
                    .memoryCacheKey("${local.absolutePath}@${local.lastModified()}")
                    .diskCacheKey("${local.absolutePath}@${local.lastModified()}")
                    .build()
            } else {
                model
            },
            contentDescription = title,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = modifier,
        )
        return
    }

    val palette = listOf(
        Color(0xFF3D6B5C), Color(0xFF6B5C3D), Color(0xFF4A5A7A),
        Color(0xFF7A4A55), Color(0xFF556B7A), Color(0xFF6B4A7A),
    )
    val background = palette[(bookId.hashCode() and 0x7FFFFFFF) % palette.size]
    Box(modifier = modifier.background(background), contentAlignment = Alignment.Center) {
        Text(
            text = title.take(1),
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}
