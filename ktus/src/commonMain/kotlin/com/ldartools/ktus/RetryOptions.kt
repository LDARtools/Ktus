package com.ldartools.ktus

//TODO docs
data class RetryOptions(
    val times:Int = 3,
    val initialDelayMillis:Long = 500,
    val maxDelayMillis:Long = 10000,
    val factor:Double = 2.0)
