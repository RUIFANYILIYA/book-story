/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.common.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val coverColors = listOf(
    Color(0xFF4E5D8C),
    Color(0xFF2F6E63),
    Color(0xFF8A4A3B),
    Color(0xFF556B3F),
    Color(0xFF6B4A78),
    Color(0xFF3D5A75),
    Color(0xFF7A5A38),
    Color(0xFF49505F)
)

private fun coverColorFor(seed: String): Color {
    var hash = 7
    for (char in seed) hash = (hash * 31 + char.code) and 0x7FFFFFFF
    return coverColors[hash % coverColors.size]
}

/**
 * Stand-in cover for books that have no cover image. The title and author are drawn
 * over a gradient picked from the title, so a book always gets the same cover.
 */
@Composable
fun BookCoverFallback(
    title: String,
    author: String,
    modifier: Modifier = Modifier
) {
    val base = remember(title) { coverColorFor(title) }
    val brush = remember(base) {
        Brush.verticalGradient(listOf(base, lerp(base, Color.Black, 0.4f)))
    }

    BoxWithConstraints(modifier = modifier.background(brush)) {
        val height = maxHeight.value
        val padding = (height * 0.085f).coerceIn(4f, 20f).dp
        val titleSize = (height * 0.115f).coerceIn(8.5f, 24f).sp
        val authorSize = (height * 0.062f).coerceIn(7f, 13f).sp

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(
                    start = padding * 0.5f,
                    top = padding * 0.7f,
                    bottom = padding * 0.7f
                )
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White.copy(0.22f))
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = titleSize,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(0.95f)
                ),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (height >= 76f && author.isNotBlank()) {
                Spacer(modifier = Modifier.height(padding * 0.4f))

                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = authorSize,
                        color = Color.White.copy(0.72f)
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
