package com.ldartools.ktus

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel

internal class TusPatchContent(
    private val source: ByteReadChannel,
    private val length: Long
) : OutgoingContent.ReadChannelContent() {
    override val contentLength = length
    override val contentType = ContentType.parse("application/offset+octet-stream")

    override fun readFrom(): ByteReadChannel = source
}