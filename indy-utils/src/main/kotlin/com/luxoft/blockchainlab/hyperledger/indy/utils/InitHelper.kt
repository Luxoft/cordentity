package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.hyperledger.indy.sdk.LibIndy


object InitHelper {
    fun init() {

        if (!LibIndy.isInitialized()) LibIndy.init("./lib/")

    }
}