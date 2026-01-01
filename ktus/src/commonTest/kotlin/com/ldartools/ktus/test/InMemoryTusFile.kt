package com.ldartools.ktus.test

import com.ldartools.ktus.ITusFile
import io.ktor.utils.io.ByteReadChannel

class InMemoryTusFile(
    private val nameValue: String,
    private val data: ByteArray
) : ITusFile {
    override val size: Long
        get() = data.size.toLong()
    override val name: String
        get() = nameValue

    override suspend fun readSection(offset: Long, length: Long): ByteReadChannel {
        val start = offset.toInt().coerceAtLeast(0)
        val endExclusive = (offset + length).toInt().coerceAtMost(data.size)
        val slice = if (start >= endExclusive) ByteArray(0) else data.copyOfRange(start, endExclusive)
        return ByteReadChannel(slice)
    }

    override suspend fun fileReadLock(): AutoCloseable {
        return AutoCloseable { /* no-op for tests */ }
    }
}