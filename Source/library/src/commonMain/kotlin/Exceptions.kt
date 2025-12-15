package io.github.ldartools.ktus
import kotlinx.io.IOException

open class TusProtocolException(message: String) : IOException(message)
class TusUploadExpiredException : TusProtocolException("Upload expired or invalid.")
class TusOffsetMismatchException(message: String) : TusProtocolException(message)
