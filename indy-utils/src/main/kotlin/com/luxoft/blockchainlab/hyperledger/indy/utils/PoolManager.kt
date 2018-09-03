package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters
import org.hyperledger.indy.sdk.pool.PoolLedgerConfigExistsException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

object PoolManager {

    const val DEFAULT_POOL_NAME = "default_pool"

    const val DEFAULT_GENESIS_FILE = "/docker_pool_transactions_genesis.txt"

    val defaultGenesisResource by lazy { File(javaClass.getResource(DEFAULT_GENESIS_FILE).toURI()) }

    private val openIndyPools = ConcurrentHashMap<String, Pool>()

    fun openIndyPool(genesisFile: File, poolName: String = DEFAULT_POOL_NAME): Pool {
        val pool = openIndyPools.getOrPut(poolName) {
            val configName = createPoolLedgerConfigFromFile(genesisFile, poolName)

            Pool.setProtocolVersion(2).get()

            val config = PoolJSONParameters.OpenPoolLedgerJSONParameter(null, null, null)

            Pool.openPoolLedger(configName, config.toJson()).get()
        }

        return pool
    }
}

fun createPoolLedgerConfigFromFile(genesisFile: File, poolName: String = PoolManager.DEFAULT_POOL_NAME): String {
    val ledgerConfig = PoolJSONParameters.CreatePoolLedgerConfigJSONParameter(genesisFile.absolutePath)

    try {
        Pool.createPoolLedgerConfig(poolName, ledgerConfig.toJson()).get()
    } catch (e: ExecutionException) {
        if(e.cause !is PoolLedgerConfigExistsException) throw e
        // ok
    }

    return poolName
}