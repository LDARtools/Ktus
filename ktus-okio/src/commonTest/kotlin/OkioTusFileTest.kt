package com.ldartools.ktus.okio.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import com.ldartools.ktus.okio.toTusFile
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import okio.SYSTEM

class OkioTusFileTest {

    private fun makeTestPath(name: String) = name.toPath()

    @Test
    fun testPropertiesAndFullRead() = runTest {
        val path = makeTestPath("okio_tus_test_full.bin")
        val content = ByteArray(1024) { (it and 0xFF).toByte() } // deterministic 1KB

        // Write file
        FileSystem.SYSTEM.sink(path).buffer().use { sink ->
            sink.write(content)
        }

        val tusFile = path.toTusFile()

        // Verify size and name
        assertEquals(content.size.toLong(), tusFile.size)
        assertEquals(path.name, tusFile.name)

        // Read full file
        val channel = tusFile.readSection(0, tusFile.size)
        val packet = channel.readRemaining()
        val read = packet.readBytes()
        assertEquals(content.size, read.size)
        assertTrue(content.contentEquals(read))
    }

    @Test
    fun testReadPartialAndPastEof() = runTest {
        val path = makeTestPath("okio_tus_test_partial.bin")
        val content = ByteArray(512) { (it and 0xFF).toByte() } // 512 bytes

        FileSystem.SYSTEM.sink(path).buffer().use { it.write(content) }

        val tusFile = path.toTusFile()

        // Read a middle slice
        val offset = 100L
        val length = 200L
        val channel = tusFile.readSection(offset, length)
        val slice = channel.readRemaining().readBytes()
        val expectedSlice = content.copyOfRange(offset.toInt(), (offset + length).toInt())
        assertTrue(expectedSlice.contentEquals(slice))

        // Request beyond EOF: ask for more than available
        val beyondOffset = 400L
        val requestLength = 200L // only 112 bytes remain (512 - 400)
        val channel2 = tusFile.readSection(beyondOffset, requestLength)
        val read2 = channel2.readRemaining().readBytes()
        val expected2 = content.copyOfRange(beyondOffset.toInt(), content.size)
        assertTrue(expected2.contentEquals(read2))
        assertTrue(read2.size < requestLength)
    }

    @Test
    fun testFileReadLockThrowsNotImplemented() = runTest {
        val path = makeTestPath("okio_tus_test_lock.bin")
        FileSystem.SYSTEM.sink(path).buffer().use { it.write(byteArrayOf(1, 2, 3)) }

        val tusFile = path.toTusFile()

        try {
            tusFile.fileReadLock()
            fail("Expected NotImplementedError from fileReadLock()")
        } catch (e: NotImplementedError) {
            // expected
        }
    }
}
