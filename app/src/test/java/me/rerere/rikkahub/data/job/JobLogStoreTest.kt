package me.rerere.rikkahub.data.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JobLogStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newStore() = JobLogStore(temp.newFolder())

    @Test
    fun appendAndReadIncrementally() {
        val store = newStore()
        val file = store.streamFile("root1", "job1", JobLogStream.STDOUT)

        store.appendText(file, "hello ")
        val first = store.readChunk(file, since = 0L, maxBytes = 1024)
        assertEquals("hello ", first.text)
        assertEquals(6L, first.to)

        store.appendText(file, "world")
        val second = store.readChunk(file, since = first.to, maxBytes = 1024)
        assertEquals("world", second.text)
        assertEquals(11L, second.totalBytes)
        assertFalse(second.cursorInvalid)
    }

    @Test
    fun rotationKeepsLogicalOffsetContinuous() {
        val store = newStore()
        val file = store.streamFile("root1", "job2", JobLogStream.STDERR)
        val chunk = "x".repeat(1024 * 1024)

        repeat(9) { store.appendText(file, chunk) }

        // 9MB 写入 -> 触发一次轮转: 逻辑总长仍是 9MB（.1 里 8MB + 当前 1MB）
        assertEquals(9L * chunk.length, store.logicalSize(file))
        // 游标跨轮转边界仍然可读
        val tail = store.readChunk(file, since = 9L * chunk.length - 10, maxBytes = 10)
        assertEquals(10, tail.text.length)
        assertEquals(9L * chunk.length, tail.to)
    }

    @Test
    fun cursorBeyondEndFallsBackToTail() {
        val store = newStore()
        val file = store.streamFile("root1", "job3", JobLogStream.STDOUT)
        store.appendText(file, "abc")

        val chunk = store.readChunk(file, since = 999L, maxBytes = 128)

        assertTrue(chunk.cursorInvalid)
        assertEquals("abc", chunk.text)
    }

    @Test
    fun screenStreamIsSeparateAndDeletable() {
        val store = newStore()
        val screen = store.streamFile("root1", "job4", JobLogStream.SCREEN)
        store.appendText(screen, "prompt$ ")

        assertEquals("prompt$ ", store.tailText(screen, 128))
        store.delete("root1", "job4")
        assertFalse(screen.exists())
        assertEquals(0L, store.logicalSize(screen))
    }
}
