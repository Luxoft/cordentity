package com.luxoft.blockchainlab.hyperledger.indy.utils

internal object EnvironmentUtils {
    val testPoolIP: String
        get() {
            val testPoolIp = System.getenv("TEST_POOL_IP")
            return testPoolIp ?: "127.0.0.1"
        }

    val tmpPath: String
        get() = System.getProperty("java.io.tmpdir") + "/indy/"
}
