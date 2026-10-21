package io.github.fanti1.audiolivre.playback

data class PlaybackState(
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isFinished: Boolean = false,
)
