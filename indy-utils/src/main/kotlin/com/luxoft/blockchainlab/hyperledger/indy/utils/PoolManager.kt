package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters
import java.io.File

object PoolManager {

    const val DEFAULT_POOL_NAME = PoolUtils.DEFAULT_POOL_NAME

    val openIndyPools = mutableMapOf<String, Pool>()

    fun openIndyPool(genesisFile: File, poolName: String = DEFAULT_POOL_NAME): Pool {
        val pool = openIndyPools.getOrPut(poolName) {
            val configName = PoolUtils.createPoolLedgerConfigFromFile(genesisFile, poolName)

            Pool.setProtocolVersion(2).get()

            val config = PoolJSONParameters.OpenPoolLedgerJSONParameter(null, null, null)

            Pool.openPoolLedger(configName, config.toJson()).get()
        }

        return pool
    }
}