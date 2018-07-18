package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.hyperledger.indy.sdk.pool.Pool

class PoolManager private constructor() {

    private val configName: String = PoolUtils.createPoolLedgerConfig()
    private val config: String = "{}"

    init {
        Pool.setProtocolVersion(2).get()
    }
    
    val pool: Pool = Pool.openPoolLedger(configName, config).get()

    companion object {
        private val mInstance: PoolManager = PoolManager()

        @Synchronized
        fun getInstance(): PoolManager {
            return mInstance
        }
    }
}