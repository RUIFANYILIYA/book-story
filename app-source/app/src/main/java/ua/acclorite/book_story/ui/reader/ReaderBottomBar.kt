/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.reader.ReaderText.Chapter
import ua.acclorite.book_story.presentation.reader.ReaderEvent
import ua.acclorite.book_story.ui.common.components.common.AnimatedVisibility
import ua.acclorite.book_story.ui.common.components.common.StyledText
import ua.acclorite.book_story.ui.common.helpers.LocalSettings
import ua.acclorite.book_story.ui.common.helpers.noRippleClickable
import kotlin.math.roundToInt

@Composable
fun ReaderBottomBar(
    chapters: List<Chapter>,
    currentChapter: Chapter?,
    lockMenu: Boolean,
    /** The whole book's progress, already formatted, e.g. "45.3%". */
    bookProgress: String,
    bottomBarPadding: Dp,
    backgroundColor: Color,
    fontColor: Color,
    scrollToChapter: (ReaderEvent.OnScrollToChapter) -> Unit,
    showChaptersDrawer: (ReaderEvent.OnShowChaptersDrawer) -> Unit,
    showSettingsBottomSheet: (ReaderEvent.OnShowSettingsBottomSheet) -> Unit
) {
    val settings = LocalSettings.current

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var brightnessSlider by remember { mutableStateOf(false) }

    val currentIndex = remember(chapters, currentChapter) {
        chapters.indexOfFirst { it.id == currentChapter?.id }
    }
    val sliderValue = if (dragging) dragValue
    else currentIndex.coerceAtLeast(0).toFloat()
    val targetChapter = chapters.getOrNull(sliderValue.roundToInt())
    val previousChapter = chapters.getOrNull(currentIndex - 1)
    val nextChapter = chapters.getOrNull(currentIndex + 1)

    Column(
        Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .noRippleClickable(onClick = {})
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 8.dp + bottomBarPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AnimatedVisibility(
            visible = brightnessSlider,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Brightness6,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = fontColor.copy(alpha = 0.7f)
                )

                Slider(
                    value = settings.screenBrightness.value,
                    onValueChange = {
                        if (!settings.customScreenBrightness.lastValue) {
                            settings.customScreenBrightness.update(true)
                        }
                        settings.screenBrightness.update(it)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    valueRange = 0f..1f,
                    colors = readerSliderColors(fontColor)
                )
            }
        }

        // The book's overall percentage stays on screen at all times. It used to be that nothing sat
        // here until a drag started, and the slider dropped a line's worth of height the moment the
        // thumb was picked up; keeping the line occupied removes that shift. While dragging, the
        // chapter being aimed at leads in front of the percentage, which still reports the book.
        val targetTitle = if (dragging) targetChapter?.title else null
        val infoStyle = MaterialTheme.typography.bodyMedium.copy(
            color = fontColor.copy(alpha = 0.7f)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Measured before the percentage, so a long chapter name is the one that gives way.
            if (targetTitle != null) {
                StyledText(
                    text = targetTitle,
                    modifier = Modifier.weight(1f, fill = false),
                    style = infoStyle,
                    maxLines = 1
                )
            }

            StyledText(
                text = if (targetTitle == null) bookProgress else " · $bookProgress",
                style = infoStyle,
                maxLines = 1
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StyledText(
                text = stringResource(id = R.string.previous_chapter),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .noRippleClickable(
                        enabled = previousChapter != null && !lockMenu
                    ) {
                        previousChapter?.let {
                            scrollToChapter(ReaderEvent.OnScrollToChapter(it))
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = fontColor.copy(alpha = if (previousChapter != null) 1f else 0.4f)
                ),
                maxLines = 1
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        chapters.getOrNull(dragValue.roundToInt())?.let {
                            scrollToChapter(ReaderEvent.OnScrollToChapter(it))
                        }
                    },
                    enabled = chapters.size > 1 && !lockMenu,
                    valueRange = 0f..(chapters.size - 1).coerceAtLeast(1).toFloat(),
                    steps = (chapters.size - 2).coerceAtLeast(0),
                    colors = readerSliderColors(fontColor)
                )
            }

            StyledText(
                text = stringResource(id = R.string.next_chapter),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .noRippleClickable(
                        enabled = nextChapter != null && !lockMenu
                    ) {
                        nextChapter?.let {
                            scrollToChapter(ReaderEvent.OnScrollToChapter(it))
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = fontColor.copy(alpha = if (nextChapter != null) 1f else 0.4f)
                ),
                maxLines = 1
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderBottomBarButton(
                icon = Icons.Outlined.MenuBook,
                label = stringResource(id = R.string.reader_menu_contents),
                color = fontColor,
                enabled = !lockMenu
            ) {
                showChaptersDrawer(ReaderEvent.OnShowChaptersDrawer)
            }

            ReaderBottomBarButton(
                icon = Icons.Outlined.Brightness6,
                label = stringResource(id = R.string.reader_menu_brightness),
                color = fontColor,
                selected = brightnessSlider,
                enabled = !lockMenu
            ) {
                brightnessSlider = !brightnessSlider
            }

            ReaderBottomBarButton(
                icon = if (settings.nightMode.value) Icons.Outlined.LightMode
                else Icons.Outlined.DarkMode,
                label = stringResource(id = R.string.reader_menu_night),
                color = fontColor,
                selected = settings.nightMode.value,
                enabled = !lockMenu
            ) {
                settings.nightMode.update(!settings.nightMode.lastValue)
            }

            ReaderBottomBarButton(
                icon = Icons.Outlined.Settings,
                label = stringResource(id = R.string.reader_menu_settings),
                color = fontColor,
                enabled = !lockMenu
            ) {
                showSettingsBottomSheet(ReaderEvent.OnShowSettingsBottomSheet)
            }
        }
    }
}

@Composable
private fun readerSliderColors(
    color: Color
) = SliderDefaults.colors(
    thumbColor = color,
    activeTrackColor = color,
    inactiveTrackColor = color.copy(alpha = 0.25f),
    disabledThumbColor = color.copy(alpha = 0.4f),
    disabledActiveTrackColor = color.copy(alpha = 0.4f),
    disabledInactiveTrackColor = color.copy(alpha = 0.15f)
)

@Composable
private fun ReaderBottomBarButton(
    icon: ImageVector,
    label: String,
    color: Color,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .noRippleClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = color.copy(alpha = if (enabled) 1f else 0.4f)
        )

        StyledText(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = color.copy(alpha = if (enabled) 1f else 0.4f)
            ),
            maxLines = 1
        )

        if (selected) {
            Box(
                Modifier
                    .size(width = 18.dp, height = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}
