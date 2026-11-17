package com.ldartools.ktus.test

import com.ldartools.ktus.isPreSendFailure
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M1: JVM/Android failures that prove the request never reached the origin. */
class PreSendFailureJvmTest {
    @Test
    fun connectAndHandshakeFailures_arePreSend() {
        for (e in listOf(java.net.ConnectException("refused"), java.net.UnknownHostException("x"),
                java.net.NoRouteToHostException("x"), java.nio.channels.UnresolvedAddressException(),
                javax.net.ssl.SSLHandshakeException("x"), io.ktor.client.network.sockets.ConnectTimeoutException("x"))) {
            assertTrue(isPreSendFailure(e), "$e")
            assertTrue(isPreSendFailure(java.io.IOException("wrapped", e)), "wrapped $e")
        }
    }

    @Test
    fun failuresAfterConnecting_areNot() {
        for (e in listOf(java.net.SocketException("Connection reset"), java.io.EOFException("eof"),
                java.net.SocketTimeoutException("read timed out"), javax.net.ssl.SSLException("bad record"))) {
            assertFalse(isPreSendFailure(e), "$e")
        }
    }
}
