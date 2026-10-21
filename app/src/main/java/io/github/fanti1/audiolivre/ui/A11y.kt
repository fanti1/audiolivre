package io.github.fanti1.audiolivre.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

// A percentage TalkBack reads out as it grows, in steps of ten: every single percent would talk over everything.
fun Modifier.announcedPercent(percent: Int): Modifier = semantics {
    liveRegion = LiveRegionMode.Polite
    contentDescription = "${percent / 10 * 10}%"
}
