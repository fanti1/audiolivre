package io.github.fanti1.audiolivre.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PageJsonTest {

    @Test
    fun `round-trips pages`() {
        val pages = listOf(
            Page(0, listOf(PageSlice(0, 0, 0, 120.5f), PageSlice(1, 0, 3, 180f)), 0, 311, opensChapter = true),
            Page(0, listOf(PageSlice(1, 4, 9, 0f)), 311, 640, opensChapter = false),
            Page(1, emptyList(), 640, 640, opensChapter = false),
        )

        assertEquals(pages, PageJson.decode(PageJson.encode(pages)))
    }
}
