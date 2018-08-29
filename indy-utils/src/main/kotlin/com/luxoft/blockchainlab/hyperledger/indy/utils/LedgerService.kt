package com.luxoft.blockchainlab.hyperledger.indy.utils

import com.luxoft.blockchainlab.hyperledger.indy.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.slf4j.LoggerFactory
import java.util.*

const val DELAY_MS = 2000
const val RETRY_TIMES = 5

class LedgerService(
        private val did: String?,
        private val wallet: Wallet,
        private val pool: Pool
) {
    private fun store(data: String) {
        val attemptId = Random().nextLong()
        logger.debug("Trying to store data on ledger [attempt id = $attemptId]: $data")
        val response = Ledger.signAndSubmitRequest(pool, wallet, did, data).get()
        logger.debug("Ledger responded [attempt id = $attemptId]: $response")
    }

    fun storeSchema(schema: Schema) {
        val schemaJson = SerializationUtils.anyToJSON(schema)
        val schemaRequest = Ledger.buildSchemaRequest(did, schemaJson).get()
        store(schemaRequest)
    }

    fun storeRevocationRegistryDefinition(revRegDef: RevocationRegistryDefinition) {
        val defJson = SerializationUtils.anyToJSON(revRegDef)
        val defRequest = Ledger.buildRevocRegDefRequest(did, defJson).get()
        store(defRequest)
    }

    fun storeRevocationRegistryEntry(revRegEntry: RevocationRegistryEntry, revRegId: String, revRegDefType: String) {
        val entryJson = SerializationUtils.anyToJSON(revRegEntry)
        val entryRequest = Ledger.buildRevocRegEntryRequest(did, revRegId, revRegDefType, entryJson).get()
        store(entryRequest)
    }

    fun storeCredentialDefinition(credDef: CredentialDefinition) {
        val credDefJson = SerializationUtils.anyToJSON(credDef)
        val request = Ledger.buildCredDefRequest(did, credDefJson).get()
        store(request)
    }

    fun retrieveSchema(schemaId: String) = LedgerService.retrieveSchema(did, pool, schemaId)
    fun retrieveCredentialDefinition(credDefId: String) = LedgerService.retrieveCredentialDefinition(did, pool, credDefId)
    fun retrieveRevocationRegistryDefinition(revRegDefId: String) = LedgerService.retrieveRevocationRegistryDefinition(did, pool, revRegDefId)
    fun retrieveRevocationRegistryEntry(revRegId: String, timestamp: Int) = LedgerService.retrieveRevocationRegistryEntry(did, pool, revRegId, timestamp)
    fun retrieveRevocationRegistryDelta(revRegDefId: String, interval: Interval) = LedgerService.retrieveRevocationRegistryDelta(did, pool, revRegDefId, interval)

    fun addNym(constructAbout: () -> IndyUser.IdentityDetails) = LedgerService.addNym(did, pool, wallet, constructAbout)
    fun addNym(about: IndyUser.IdentityDetails) = LedgerService.addNym(did, pool, wallet) { about }

    companion object {
        val logger = LoggerFactory.getLogger(IndyUser::class.java.name)!!

        fun addNym(did: String?, pool: Pool, wallet: Wallet, constructAbout: () -> IndyUser.IdentityDetails) {
            val about = constructAbout()

            val nymRequest = Ledger.buildNymRequest(
                    did,
                    about.did,
                    about.verkey,
                    about.alias,
                    about.role
            ).get()

            Ledger.signAndSubmitRequest(pool, wallet, did, nymRequest).get()
        }

        fun retrieveSchema(did: String?, pool: Pool, schemaId: String): Schema? = runBlocking {
            val result: Schema? = null

            repeat(RETRY_TIMES) {
                try {
                    val schemaReq = Ledger.buildGetSchemaRequest(did, schemaId).get()
                    val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
                    val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()

                    return@runBlocking SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)
                } catch (e: Exception) {
                    logger.debug("Schema retrieving failed (id: $schemaId). Retry attempt $it")
                    delay(DELAY_MS)
                }
            }

            result
        }

        fun retrieveCredentialDefinition(did: String?, pool: Pool, credDefId: String): CredentialDefinition? = runBlocking {
            val result: CredentialDefinition? = null

            repeat(RETRY_TIMES) {
                try {
                    val getCredDefRequest = Ledger.buildGetCredDefRequest(did, credDefId).get()
                    val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
                    val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()

                    return@runBlocking SerializationUtils.jSONToAny<CredentialDefinition>(credDefIdInfo.objectJson)
                } catch (e: Exception) {
                    logger.debug("Credential definition retrieving failed (id: $credDefId). Retry attempt $it")
                    delay(DELAY_MS)
                }
            }

            result
        }

        fun retrieveRevocationRegistryDefinition(did: String?, pool: Pool, revRegDefId: String): RevocationRegistryDefinition? = runBlocking {
            val result: RevocationRegistryDefinition? = null

            repeat(RETRY_TIMES) {
                try {
                    val request = Ledger.buildGetRevocRegDefRequest(did, revRegDefId).get()
                    val response = Ledger.submitRequest(pool, request).get()
                    val revRegDefJson = Ledger.parseGetRevocRegDefResponse(response).get().objectJson

                    return@runBlocking SerializationUtils.jSONToAny<RevocationRegistryDefinition>(revRegDefJson)
                } catch (e: Exception) {
                    logger.debug("Revocation registry definition retrieving failed (id: $revRegDefId). Retry attempt $it")
                    delay(DELAY_MS)
                }
            }

            result
        }

        fun retrieveRevocationRegistryEntry(did: String?, pool: Pool, revRegId: String, timestamp: Int): Pair<Int, RevocationRegistryEntry>? = runBlocking {
            val result: Pair<Int, RevocationRegistryEntry>? = null

            repeat(RETRY_TIMES) {
                try {
                    val request = Ledger.buildGetRevocRegRequest(did, revRegId, timestamp).get()
                    val response = Ledger.submitRequest(pool, request).get()
                    val revReg = Ledger.parseGetRevocRegResponse(response).get()

                    val tmsp = revReg.timestamp
                    val revRegEntry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revReg.objectJson)

                    return@runBlocking Pair(tmsp, revRegEntry)
                } catch (e: Exception) {
                    logger.debug("Revocation registry entry retrieving failed (id: $revRegId, timestamp: $timestamp). Retry attempt $it")
                    delay(DELAY_MS)
                }
            }

            result
        }

        fun retrieveRevocationRegistryDelta(did: String?, pool: Pool, revRegDefId: String, interval: Interval): Pair<Int, RevocationRegistryEntry>? = runBlocking {
            val result: Pair<Int, RevocationRegistryEntry>? = null

            repeat(RETRY_TIMES) {
                try {
                    val from = interval.from ?: -1

                    val request = Ledger.buildGetRevocRegDeltaRequest(did, revRegDefId, from, interval.to).get()
                    val response = Ledger.submitRequest(pool, request).get()
                    val revRegDeltaJson = Ledger.parseGetRevocRegDeltaResponse(response).get()

                    val timestamp = revRegDeltaJson.timestamp
                    val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson.objectJson)

                    return@runBlocking Pair(timestamp, revRegDelta)
                } catch (e: Exception) {
                    logger.debug("Revocation registry delta retrieving failed (id: $revRegDefId, interval: $interval). Retry attempt $it")
                    delay(DELAY_MS)
                }
            }

            result
        }
    }
}