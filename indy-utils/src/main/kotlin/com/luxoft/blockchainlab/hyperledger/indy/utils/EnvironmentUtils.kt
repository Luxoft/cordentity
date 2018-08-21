package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.apache.commons.io.FileUtils.getUserDirectoryPath


internal object EnvironmentUtils {
    val testPoolIP: String
        get() {
            val testPoolIp = System.getenv("TEST_POOL_IP")
            return testPoolIp ?: "127.0.0.1"
        }

    fun getCurrentUnixEpochTime() = (System.currentTimeMillis() / 1000).toInt()

    val tmpPath: String
        get() = System.getProperty("java.io.tmpdir") + "/indy/"

    fun getIndyHomePath(): String {
        return getUserDirectoryPath() + "/.indy_client/"
    }

    fun getIndyHomePath(filename: String): String {
        return getIndyHomePath() + filename
    }
}
