package com.luxoft.blockchainlab.hyperledger.indy.utils

import com.luxoft.blockchainlab.hyperledger.indy.*
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
    companion object {
        val logger = LoggerFactory.getLogger(IndyUser::class.java.name)!!
    }

    private fun store(data: String) {
        val attemptId = Random().nextLong()
        logger.debug("Trying to store data on ledger [attempt id = $attemptId]: $data")
        val response = Ledger.signAndSubmitRequest(pool, wallet, did, data).get()
        logger.debug("Ledger responded [attempt id = $attemptId]: $response")
    }

    fun nymFor(identityDetails: IndyUser.IdentityDetails) {
        val nymRequest = Ledger.buildNymRequest(
                did,
                identityDetails.did,
                identityDetails.verkey,
                identityDetails.alias,
                identityDetails.role
        ).get()

        Ledger.signAndSubmitRequest(pool, wallet, did, nymRequest).get()
    }

    fun storeSchema(schema: Schema) {
        val schemaJson = SerializationUtils.anyToJSON(schema)
        storeSchema(schemaJson)
    }

    fun storeSchema(schemaJson: String) {
        val schemaRequest = Ledger.buildSchemaRequest(did, schemaJson).get()
        store(schemaRequest)
    }

    fun storeRevocationRegistryDefinition(revRegDef: RevocationRegistryDefinition) {
        val defJson = SerializationUtils.anyToJSON(revRegDef)
        storeRevocationRegistryDefinition(defJson)
    }

    fun storeRevocationRegistryDefinition(revRegDefJson: String) {
        val defRequest = Ledger.buildRevocRegDefRequest(did, revRegDefJson).get()
        store(defRequest)
    }

    fun storeRevocationRegistryEntry(revRegEntry: RevocationRegistryEntry, revRegId: String, revRegDefType: String) {
        val entryJson = SerializationUtils.anyToJSON(revRegEntry)
        storeRevocationRegistryEntry(entryJson, revRegId, revRegDefType)
    }

    fun storeRevocationRegistryEntry(revRegEntryJson: String, revRegId: String, revRegDefType: String) {
        val entryRequest = Ledger.buildRevocRegEntryRequest(
                did,
                revRegId,
                revRegDefType,
                revRegEntryJson
        ).get()
        store(entryRequest)
    }

    fun storeCredentialDefinition(credDef: CredentialDefinition) {
        val credDefJson = SerializationUtils.anyToJSON(credDef)
        storeCredentialDefinition(credDefJson)
    }

    fun storeCredentialDefinition(credDefJson: String) {
        val request = Ledger.buildCredDefRequest(did, credDefJson).get()
        store(request)
    }

    fun retrieveSchema(schemaId: String): Schema {
        val schemaReq = Ledger.buildGetSchemaRequest(did, schemaId).get()
        val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
        val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()

        val schema = SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)
                ?: throw RuntimeException("Unable to parse schema from json")

        if (!schema.isValid())
            throw IndyUser.ArtifactDoesntExist(schemaId)

        return schema
    }

    fun retrieveCredentialDefinition(credDefId: String): CredentialDefinition {
        val getCredDefRequest = Ledger.buildGetCredDefRequest(did, credDefId).get()
        val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
        val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()

        return SerializationUtils.jSONToAny(credDefIdInfo.objectJson)
                ?: throw RuntimeException("Unable to parse credential definition from json")
    }

    fun retrieveRevocationRegistryDefinition(revRegDefId: String): RevocationRegistryDefinition {
        val request = Ledger.buildGetRevocRegDefRequest(did, revRegDefId).get()
        val response = Ledger.submitRequest(pool, request).get()
        val revRegDefJson = Ledger.parseGetRevocRegDefResponse(response).get().objectJson

        return SerializationUtils.jSONToAny(revRegDefJson)
                ?: throw RuntimeException("Unable to parse revocation registry definition from json from ledger")
    }

    fun retrieveRevocationRegistryEntry(revRegId: String, timestamp: Int = EnvironmentUtils.getCurrentUnixEpochTime()): RevocationRegistryEntry {
        val request = Ledger.buildGetRevocRegRequest(did, revRegId, timestamp).get()
        val response = Ledger.submitRequest(pool, request).get()
        val revRegJson = Ledger.parseGetRevocRegResponse(response).get().objectJson

        return SerializationUtils.jSONToAny(revRegJson)
                ?: throw RuntimeException("Unable to parse revocation registry entry from json from ledger")
    }

    // returns json for now
    fun retrieveRevocationRegistryDelta(
            revRegDefId: String,
            from: Int = -1,
            to: Int = EnvironmentUtils.getCurrentUnixEpochTime()
    ): String {
        val request = Ledger.buildGetRevocRegDeltaRequest(did, revRegDefId, from, to).get()
        val response = Ledger.submitRequest(pool, request).get()
        val revRegDeltaJson = Ledger.parseGetRevocRegDeltaResponse(response).get()

        return revRegDeltaJson.objectJson
    }
}