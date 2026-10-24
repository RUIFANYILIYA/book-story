/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.start

import androidx.compose.runtime.Composable

@Composable
fun StartDone(
    navigateToLibrary: () -> Unit,
    navigateToHelp: () -> Unit
) {
    StartDoneScaffold(
        navigateToLibrary = navigateToLibrary,
        navigateToHelp = navigateToHelp
    )
}