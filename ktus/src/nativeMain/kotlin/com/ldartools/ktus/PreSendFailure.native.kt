package com.ldartools.ktus

// Darwin errors are recognised from their NSError text in commonMain (isDarwinPreSendFailure); Ktor's
// curl/CIO engines on Linux report connect failures as ConnectTimeoutException or plain IOExceptions.
internal actual fun isPlatformPreSendFailure(error: Throwable): Boolean = false
