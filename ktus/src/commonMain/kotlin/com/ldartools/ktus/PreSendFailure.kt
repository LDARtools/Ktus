package com.ldartools.ktus

import io.ktor.client.network.sockets.ConnectTimeoutException

/**
 * True when [error] proves the request never reached the origin: the connection could not be opened
 * (refused, unreachable, DNS failure, no network, connect timeout) or the TLS handshake failed. Ktus then
 * knows the origin is not still processing the request.
 *
 * Unknown failures return false ("the origin may still be processing it"), which is the conservative answer
 * for overlapping writes.
 */
internal fun isPreSendFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 16) {
        if (current is ConnectTimeoutException) return true
        if (isPlatformPreSendFailure(current)) return true
        if (isDarwinPreSendFailure(current)) return true
        current = current.cause.takeIf { it !== current }
        depth++
    }
    return false
}

/** JVM/Android: java.net / javax.net.ssl connect and handshake failures. Native: none (see the Darwin check). */
internal expect fun isPlatformPreSendFailure(error: Throwable): Boolean

/**
 * NSURLErrorDomain codes (Apple's NSURLError.h) that mean the request was never sent: the host could not be
 * found or reached, there is no network, or the secure connection could not be set up. Ktor's Darwin engine
 * reports them as `DarwinHttpRequestException`, whose message contains the NSError description, for example
 * `Error Domain=NSURLErrorDomain Code=-1004 "Could not connect to the server."`. Matching the text avoids a
 * dependency on the Darwin engine.
 */
// Only codes that cannot happen after request bytes were sent. Deliberately left out (they then count as "the
// origin may still be processing", the conservative side): -1009 NotConnectedToInternet (Apple: "probably"
// did not leave the device, not reliably), -1018/-1019/-1020 (path conditions that can appear mid-request) and
// -1200 SecureConnectionFailed (can come from a TLS abort mid-connection).
private val DARWIN_PRE_SEND_CODES = setOf(
    -1003, // NSURLErrorCannotFindHost
    -1004, // NSURLErrorCannotConnectToHost
    -1006, // NSURLErrorDNSLookupFailed
    -1022, // NSURLErrorAppTransportSecurityRequiresSecureConnection
    // Certificate validation failures happen during the TLS handshake, before the request is sent.
    -1201, // NSURLErrorServerCertificateHasBadDate
    -1202, // NSURLErrorServerCertificateUntrusted
    -1203, // NSURLErrorServerCertificateHasUnknownRoot
    -1204, // NSURLErrorServerCertificateNotYetValid
    -1205, // NSURLErrorClientCertificateRejected
    -1206, // NSURLErrorClientCertificateRequired
)

private val NSURL_ERROR_CODE = Regex("""NSURLErrorDomain Code=(-?\d+)""")

internal fun isDarwinPreSendFailure(error: Throwable): Boolean {
    val code = NSURL_ERROR_CODE.find(error.message ?: return false)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return code in DARWIN_PRE_SEND_CODES
}
