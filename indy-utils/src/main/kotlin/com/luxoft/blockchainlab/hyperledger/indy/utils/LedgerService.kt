package com.luxoft.blockchainlab.hyperledger.indy.utils

import com.luxoft.blockchainlab.hyperledger.indy.*
import org.hyperledger.indy.sdk.IndyException
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.slf4j.LoggerFactory
import java.util.*

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

        fun retrieveSchema(did: String?, pool: Pool, schemaId: String): Schema? {
            return try {
                val schemaReq = Ledger.buildGetSchemaRequest(did, schemaId).get()
                val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
                val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()

                SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)
                        ?: throw RuntimeException("Unable to parse schema from json")

            } catch (e: IndyException) {
                logger.error("", e)
                null
            }
        }

        fun retrieveCredentialDefinition(did: String?, pool: Pool, credDefId: String): CredentialDefinition? {
            return try {
                val getCredDefRequest = Ledger.buildGetCredDefRequest(did, credDefId).get()
                val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
                val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()

                SerializationUtils.jSONToAny(credDefIdInfo.objectJson)
                        ?: throw RuntimeException("Unable to parse credential definition from json")
            } catch (e: IndyException) {
                logger.error("", e)
                null
            }
        }

        fun retrieveRevocationRegistryDefinition(did: String?, pool: Pool, revRegDefId: String): RevocationRegistryDefinition? {
            return try {
                val request = Ledger.buildGetRevocRegDefRequest(did, revRegDefId).get()
                val response = Ledger.submitRequest(pool, request).get()
                val revRegDefJson = Ledger.parseGetRevocRegDefResponse(response).get().objectJson

                SerializationUtils.jSONToAny(revRegDefJson)
                        ?: throw RuntimeException("Unable to parse revocation registry definition from json from ledger")
            } catch (e: IndyException) {
                logger.error("", e)
                null
            }
        }

        fun retrieveRevocationRegistryEntry(did: String?, pool: Pool, revRegId: String, timestamp: Int): Pair<Int, RevocationRegistryEntry>? {
            return try {
                val request = Ledger.buildGetRevocRegRequest(did, revRegId, timestamp).get()
                val response = Ledger.submitRequest(pool, request).get()
                val revReg = Ledger.parseGetRevocRegResponse(response).get()

                val tmsp = revReg.timestamp
                val revRegEntry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revReg.objectJson)
                        ?: throw RuntimeException("Unable to parse revocation registry entry from json from ledger")

                Pair(tmsp, revRegEntry)
            } catch (e: IndyException) {
                logger.error("", e)
                null
            }
        }

        fun retrieveRevocationRegistryDelta(
                did: String?, pool: Pool,
                revRegDefId: String,
                interval: Interval
        ): Pair<Int, RevocationRegistryEntry>? {
            return try {
                val from = interval.from ?: -1

                val request = Ledger.buildGetRevocRegDeltaRequest(did, revRegDefId, from, interval.to).get()
                val response = Ledger.submitRequest(pool, request).get()
                val revRegDeltaJson = Ledger.parseGetRevocRegDeltaResponse(response).get()

                val timestamp = revRegDeltaJson.timestamp
                val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson.objectJson)
                        ?: throw RuntimeException("Unable to parse revocation registry delta from json from ledger")

                Pair(timestamp, revRegDelta)
            } catch (e: IndyException) {
                logger.error("", e)
                null
            }
        }
    }
}