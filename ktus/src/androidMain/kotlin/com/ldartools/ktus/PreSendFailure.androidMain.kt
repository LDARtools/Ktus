package com.ldartools.ktus

internal actual fun isPlatformPreSendFailure(error: Throwable): Boolean =
    error is java.net.ConnectException || // connection refused (OkHttp, CIO, HttpURLConnection)
        error is java.net.UnknownHostException ||
        error is java.net.NoRouteToHostException ||
        error is java.net.PortUnreachableException ||
        error is java.nio.channels.UnresolvedAddressException ||
        error is javax.net.ssl.SSLHandshakeException
