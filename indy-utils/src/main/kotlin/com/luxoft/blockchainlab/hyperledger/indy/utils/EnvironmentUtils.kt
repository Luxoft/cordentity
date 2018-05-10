package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.apache.commons.io.FileUtils

internal object EnvironmentUtils {
    val testPoolIP: String
        get() {
            val testPoolIp = System.getenv("TEST_POOL_IP")
            return testPoolIp ?: "127.0.0.1"
        }

    val tmpPath: String
        get() = FileUtils.getTempDirectoryPath() + "/indy/"

    fun getTmpPath(filename: String): String {
        return tmpPath + filename
    }
}
