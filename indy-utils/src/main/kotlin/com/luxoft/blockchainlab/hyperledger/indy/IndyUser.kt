package com.luxoft.blockchainlab.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonIgnore
import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils.getIndyHomePath
import com.luxoft.blockchainlab.hyperledger.indy.utils.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import net.corda.core.serialization.CordaSerializable
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.blob_storage.BlobStorageReader
import org.hyperledger.indy.sdk.blob_storage.BlobStorageWriter
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.pairwise.Pairwise
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletItemNotFoundException
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException


/**
 * The central class that encapsulates Indy SDK calls and keeps the corresponding state.
 *
 * Create one instance per each server node that deals with Indy Ledger.
 */
open class IndyUser {

    @CordaSerializable
    data class IdentityDetails(
            val did: String,
            val verkey: String,
            @JsonIgnore val alias: String?,
            @JsonIgnore val role: String?
    ) {
        @JsonIgnore
        fun getIdentityRecord() = """{"did":"$did","verkey":"$verkey"}"""
    }

    companion object {
        const val SIGNATURE_TYPE = "CL"
        const val REVOCATION_REGISTRY_TYPE = "CL_ACCUM"
        const val TAG = "TAG_1"
        const val REVOCATION_TAG = "REV_TAG_1"
        private const val ISSUANCE_ON_DEMAND = "ISSUANCE_ON_DEMAND"
        private const val EMPTY_OBJECT = "{}"

        fun buildSchemaId(did: String, name: String, version: String): String = "$did:2:$name:$version"
        fun buildCredentialDefinitionId(did: String, schemaSeqNo: Int): String = "$did:3:$SIGNATURE_TYPE:$schemaSeqNo:$TAG"
        fun buildRevocationRegistryDefinitionId(did: String, credDefId: String): String = "$did:4:$credDefId:$REVOCATION_REGISTRY_TYPE:$REVOCATION_TAG"

        fun getTailsConfig() = """{"base_dir":"${getIndyHomePath("tails")}","uri_pattern":""}"""
                .replace('\\', '/')

        fun getCredentialDefinitionConfig() = """{"support_revocation":true}"""

        /**
         * Verifies proof produced by prover
         *
         * @param proofReq          proof request used by prover to create proof
         * @param proof             proof created by prover
         * @param usedData          some data from ledger needed to verify proof
         *
         * @return true/false       does proof valid?
         */
        fun verifyProof(proofReq: ProofRequest, proof: ProofInfo, usedData: DataUsedInProofJson): Boolean {
            val proofRequestJson = SerializationUtils.anyToJSON(proofReq)
            val proofJson = SerializationUtils.anyToJSON(proof.proofData)

            return Anoncreds.verifierVerifyProof(
                    proofRequestJson, proofJson, usedData.schemas, usedData.credentialDefinitions, usedData.revocationRegistryDefinitions, usedData.revocationRegistries
            ).get()
        }

        /**
         * Gets from ledger all data needed to verify proof
         *
         * @param did               verifier did
         * @param pool              ledger pool object
         * @param proofRequest      proof request used by prover to create proof
         * @param proof             proof created by prover
         *
         * @return                  used data in json wrapped in object
         */
        fun getDataUsedInProof(did: String, pool: Pool, proofRequest: ProofRequest, proof: ProofInfo): DataUsedInProofJson {
            val usedSchemas = proof.proofData.identifiers
                    .map { it.schemaId }
                    .distinct()
                    .map {
                        LedgerService.retrieveSchema(did, pool, it)
                                ?: throw RuntimeException("Schema $it doesn't exist in ledger")
                    }
                    .associate { it.id to it }
            val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)

            val usedCredentialDefs = proof.proofData.identifiers
                    .map { it.credentialDefinitionId }
                    .distinct()
                    .map {
                        LedgerService.retrieveCredentialDefinition(did, pool, it)
                                ?: throw RuntimeException("Credential definition $it doesn't exist in ledger")
                    }
                    .associate { it.id to it }
            val usedCredentialDefsJson = SerializationUtils.anyToJSON(usedCredentialDefs)

            val (revRegDefsJson, revRegDeltasJson) = if (proofRequest.nonRevoked != null) {
                val revRegDefs = proof.proofData.identifiers
                        .map { it.revocationRegistryId!! }
                        .distinct()
                        .map {
                            LedgerService.retrieveRevocationRegistryDefinition(did, pool, it)
                                    ?: throw RuntimeException("Revocation registry definition $it doesn't exist in ledger")
                        }
                        .associate { it.id to it }

                val revRegDeltas = proof.proofData.identifiers
                        .map { Pair(it.revocationRegistryId!!, it.timestamp!!) }
                        .distinct()
                        .associate { (revRegId, timestamp) ->
                            val response = LedgerService.retrieveRevocationRegistryEntry(did, pool, revRegId, timestamp)
                                    ?: throw RuntimeException("Revocation registry for definition $revRegId at timestamp $timestamp doesn't exist in ledger")

                            val (tmstmp, revReg) = response
                            val map = hashMapOf<Long, RevocationRegistryEntry>()
                            map[tmstmp] = revReg

                            revRegId to map
                        }

                Pair(SerializationUtils.anyToJSON(revRegDefs), SerializationUtils.anyToJSON(revRegDeltas))
            } else Pair(EMPTY_OBJECT, EMPTY_OBJECT)

            return DataUsedInProofJson(usedSchemasJson, usedCredentialDefsJson, revRegDefsJson, revRegDeltasJson)
        }

        /**
         * Creates proof request
         *
         * @param version           (???)
         * @param name              name of this proof request
         * @param nonce             some random number to distinguish identical proof requests
         * @param attributes        attributes which prover needs to reveal
         * @param predicates        predicates which prover should answer
         * @param nonRevoked        <optional> time interval of [attributes] and [predicates] non-revocation
         *
         * @return                  proof request
         */
        fun createProofRequest(
                version: String = "0.1",
                name: String = "proof_req_$version",
                nonce: String = "123432421212",
                attributes: List<CredentialFieldReference>,
                predicates: List<CredentialPredicate>,
                nonRevoked: Interval? = null
        ): ProofRequest {

            val requestedAttributes = attributes
                    .withIndex()
                    .associate { attr ->
                        attr.value.fieldName to CredentialAttributeReference(
                                attr.value.fieldName,
                                attr.value.schemaId
                        )
                    }

            val requestedPredicates = predicates
                    .withIndex()
                    .associate { predicate ->
                        predicate.value.fieldReference.fieldName to CredentialPredicateReference(
                                predicate.value.fieldReference.fieldName,
                                predicate.value.type,
                                predicate.value.value,
                                predicate.value.fieldReference.schemaId
                        )
                    }

            return ProofRequest(version, name, nonce, requestedAttributes, requestedPredicates, nonRevoked)
        }
    }

    private val logger = LoggerFactory.getLogger(IndyUser::class.java.name)

    val defaultMasterSecretId = "master"
    val did: String
    val verkey: String

    protected val wallet: Wallet
    protected val pool: Pool

    private var ledgerService: LedgerService

    constructor(pool: Pool, wallet: Wallet, did: String?, didConfig: String = EMPTY_OBJECT) {

        this.pool = pool
        this.wallet = wallet

        var newDid: String
        var newVerkey: String

        if (did != null) {
            try {
                newDid = did
                newVerkey = Did.keyForLocalDid(wallet, did).get()

            } catch (ex: ExecutionException) {
                if (getRootCause(ex) !is WalletItemNotFoundException) throw ex

                val didResult = Did.createAndStoreMyDid(wallet, didConfig).get()
                newDid = didResult.did
                newVerkey = didResult.verkey
            }
        } else {
            val didResult = Did.createAndStoreMyDid(wallet, didConfig).get()
            newDid = didResult.did
            newVerkey = didResult.verkey
        }

        this.did = newDid
        this.verkey = newVerkey

        ledgerService = LedgerService(this.did, this.wallet, this.pool)
    }

    /**
     * Closes wallet of this indy user
     */
    fun close() {
        wallet.closeWallet().get()
    }

    /**
     * Gets identity details by did
     *
     * @param did           target did
     *
     * @return              identity details
     */
    fun getIdentity(did: String): IdentityDetails {
        return IdentityDetails(did, Did.keyForDid(pool, wallet, did).get(), null, null)
    }

    /**
     * Gets identity details of this indy user
     */
    fun getIdentity() = getIdentity(did)

    /**
     * Adds provided identity to whitelist
     *
     * @param identityDetails
     */
    fun addKnownIdentities(identityDetails: IdentityDetails) {
        Did.storeTheirDid(wallet, identityDetails.getIdentityRecord()).get()
    }

    /**
     * (for trustee)
     * Shares rights to write to ledger with provided identity
     *
     * @param identityDetails
     */
    fun setPermissionsFor(identityDetails: IdentityDetails) {
        addKnownIdentities(identityDetails)
        ledgerService.addNym(identityDetails)
    }

    /**
     * Creates master secret by it's id
     *
     * @param masterSecretId
     */
    fun createMasterSecret(masterSecretId: String) {
        try {
            Anoncreds.proverCreateMasterSecret(wallet, masterSecretId).get()
        } catch (e: ExecutionException) {
            if (getRootCause(e) !is DuplicateMasterSecretNameException) throw e

            logger.debug("MasterSecret already exists, who cares, continuing")
        }
    }

    /**
     * Creates temporary did which can be used by identity to perform some any operations
     *
     * @param identityRecord            identity details
     *
     * @return                          newly created did
     */
    fun createSessionDid(identityRecord: IdentityDetails): String {
        if (!Pairwise.isPairwiseExists(wallet, identityRecord.did).get()) {
            addKnownIdentities(identityRecord)
            val sessionDid = Did.createAndStoreMyDid(wallet, EMPTY_OBJECT).get().did
            Pairwise.createPairwise(wallet, identityRecord.did, sessionDid, "").get()
        }

        val pairwiseJson = Pairwise.getPairwise(wallet, identityRecord.did).get()
        val pairwise = SerializationUtils.jSONToAny<ParsedPairwise>(pairwiseJson)

        return pairwise.myDid
    }

    /**
     * Creates new schema and stores it to ledger if not exists, else restores schema from ledger
     *
     * @param name                      new schema name
     * @param version                   schema version (???)
     * @param attributes                schema attributes
     *
     * @return                          created schema
     */
    fun createSchema(name: String, version: String, attributes: List<String>): Schema {
        val attrStr = attributes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        val schemaId = buildSchemaId(did, name, version)
        val schemaFromLedger = ledgerService.retrieveSchema(schemaId)

        return if (schemaFromLedger == null) {
            val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attrStr).get()

            val schema = SerializationUtils.jSONToAny<Schema>(schemaInfo.schemaJson)

            ledgerService.storeSchema(schema)

            schema
        } else schemaFromLedger
    }

    /**
     * Creates credential definition and stores it to ledger if not exists, else restores credential definition from ledger
     *
     * @param schemaId                  id of schema to create credential definition for
     * @param enableRevocation          whether enable or disable revocation for this credential definition
     *                                  (hint) turn this on by default, but just don't revoke credentials
     *
     * @return                          created credential definition
     */
    fun createCredentialDefinition(schemaId: String, enableRevocation: Boolean): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId)
                ?: throw IndySchemaNotFoundException(schemaId, "Create credential definition has been failed")
        val schemaJson = SerializationUtils.anyToJSON(schema)

        val credDefConfigJson = if (enableRevocation) getCredentialDefinitionConfig() else EMPTY_OBJECT

        val credDefId = buildCredentialDefinitionId(did, schema.seqNo!!)
        val credDefFromLedger = ledgerService.retrieveCredentialDefinition(credDefId)

        return if (credDefFromLedger == null) {
            val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(
                    wallet, did, schemaJson, TAG, SIGNATURE_TYPE, credDefConfigJson
            ).get()

            val credDef = SerializationUtils.jSONToAny<CredentialDefinition>(credDefInfo.credDefJson)

            ledgerService.storeCredentialDefinition(credDef)

            credDef
        } else credDefFromLedger
    }

    /**
     * Creates revocation registry for credential definition if there's no one in ledger
     * (usable only for those credential definition for which enableRevocation = true)
     *
     * @param credentialDefinitionId    credential definition id
     * @param maxCredentialNumber       maximum number of credentials which can be issued for this credential definition
     *                                  (example) driver agency can produce only 1000 driver licences per year
     *
     * @return                          created
     */
    fun createRevocationRegistry(credentialDefinitionId: String, maxCredentialNumber: Int = 5): RevocationRegistryInfo {
        val revRegDefConfig = RevocationRegistryConfig(ISSUANCE_ON_DEMAND, maxCredentialNumber)
        val revRegDefConfigJson = SerializationUtils.anyToJSON(revRegDefConfig)
        val tailsWriter = getTailsHandler().writer

        val revRegId = buildRevocationRegistryDefinitionId(did, credentialDefinitionId)
        val definitionFromLedger = ledgerService.retrieveRevocationRegistryDefinition(revRegId)

        if (definitionFromLedger == null) {
            val createRevRegResult =
                    Anoncreds.issuerCreateAndStoreRevocReg(
                            wallet, did, null, REVOCATION_TAG, credentialDefinitionId, revRegDefConfigJson, tailsWriter
                    ).get()

            val definition = SerializationUtils.jSONToAny<RevocationRegistryDefinition>(createRevRegResult.revRegDefJson)
            val entry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createRevRegResult.revRegEntryJson)

            ledgerService.storeRevocationRegistryDefinition(definition)
            ledgerService.storeRevocationRegistryEntry(entry, definition.id, definition.revocationRegistryDefinitionType)

            return RevocationRegistryInfo(definition, entry)
        }

        val entryFromLedger = ledgerService.retrieveRevocationRegistryEntry(revRegId, Timestamp.now())
                ?: throw RuntimeException("Unable to get revocation registry entry of existing definition $revRegId from ledger")

        return RevocationRegistryInfo(definitionFromLedger, entryFromLedger.second)
    }

    /**
     * Creates credential offer
     *
     * @param credentialDefinitionId    credential definition id
     *
     * @return                          created credential offer
     */
    fun createCredentialOffer(credentialDefinitionId: String): CredentialOffer {
        val credOfferJson = Anoncreds.issuerCreateCredentialOffer(wallet, credentialDefinitionId).get()

        return SerializationUtils.jSONToAny(credOfferJson)
    }

    /**
     * Creates credential request
     *
     * @param proverDid                 prover's did
     * @param offer                     credential offer
     * @param masterSecretId            <optional> master secret id
     *
     * @return                          credential request and all reliable data
     */
    fun createCredentialRequest(proverDid: String, offer: CredentialOffer, masterSecretId: String = defaultMasterSecretId): CredentialRequestInfo {
        val credDef = ledgerService.retrieveCredentialDefinition(offer.credentialDefinitionId)
                ?: throw IndyCredentialDefinitionNotFoundException(offer.credentialDefinitionId, "Create credential request has been failed")

        val credentialOfferJson = SerializationUtils.anyToJSON(offer)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        createMasterSecret(masterSecretId)

        val credReq = Anoncreds.proverCreateCredentialReq(
                wallet, proverDid, credentialOfferJson, credDefJson, masterSecretId
        ).get()

        val credentialRequest = SerializationUtils.jSONToAny<CredentialRequest>(credReq.credentialRequestJson)
        val credentialRequestMetadata = SerializationUtils.jSONToAny<CredentialRequestMetadata>(credReq.credentialRequestMetadataJson)

        return CredentialRequestInfo(credentialRequest, credentialRequestMetadata)
    }

    /**
     * Issues credential by credential request. If revocation is enabled it will hold one of [maxCredentialNumber].
     *
     * @param credentialRequest         credential request and all reliable info
     * @param proposal                  credential proposal
     * @param offer                     credential offer
     * @param revocationRegistryId      <optional> revocation registry definition ID
     *
     * @return                          credential and all reliable info
     */
    fun issueCredential(credentialRequest: CredentialRequestInfo, proposal: String, offer: CredentialOffer, revocationRegistryId: String?): CredentialInfo {
        val credentialRequestJson = SerializationUtils.anyToJSON(credentialRequest.request)
        val credentialOfferJson = SerializationUtils.anyToJSON(offer)
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

        val createCredentialResult = Anoncreds.issuerCreateCredential(
                wallet,
                credentialOfferJson,
                credentialRequestJson,
                proposal,
                revocationRegistryId,
                tailsReaderHandle
        ).get()

        val credential = SerializationUtils.jSONToAny<Credential>(createCredentialResult.credentialJson)

        if (revocationRegistryId != null) {
            val revocationRegistryDefinition = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId)
                    ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Issue credential has been failed")

            val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createCredentialResult.revocRegDeltaJson)

            ledgerService.storeRevocationRegistryEntry(revRegDelta, revocationRegistryId, revocationRegistryDefinition.revocationRegistryDefinitionType)
        }

        return CredentialInfo(credential, createCredentialResult.revocId, createCredentialResult.revocRegDeltaJson)
    }

    /**
     * Revokes previously issued credential
     *
     * @param revocationRegistryId      revocation registry definition id
     * @param credentialRevocationId    revocation registry credential index
     */
    fun revokeCredential(revocationRegistryId: String, credentialRevocationId: String) {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle
        val revRegDeltaJson = Anoncreds.issuerRevokeCredential(wallet, tailsReaderHandle, revocationRegistryId, credentialRevocationId).get()
        val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson)
        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId)
                ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Revoke credential has been failed")

        ledgerService.storeRevocationRegistryEntry(revRegDelta, revocationRegistryId, revRegDef.revocationRegistryDefinitionType)
    }

    /**
     * Stores credential in prover's wallet
     *
     * @param credentialInfo            credential and all reliable data
     * @param credentialRequest         credential request and all reliable data
     * @param offer                     credential offer
     */
    fun receiveCredential(credentialInfo: CredentialInfo, credentialRequest: CredentialRequestInfo, offer: CredentialOffer) {
        val revRegDefJson = if (credentialInfo.credential.revocationRegistryId != null) {
            val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(credentialInfo.credential.revocationRegistryId)
                    ?: throw IndyRevRegNotFoundException(credentialInfo.credential.revocationRegistryId, "Receive credential has been failed")

            SerializationUtils.anyToJSON(revRegDef)
        } else null

        val credDef = ledgerService.retrieveCredentialDefinition(offer.credentialDefinitionId)
                ?: throw IndyCredentialDefinitionNotFoundException(offer.credentialDefinitionId, "Receive credential has been failed")

        val credentialJson = SerializationUtils.anyToJSON(credentialInfo.credential)
        val credentialRequestMetadataJson = SerializationUtils.anyToJSON(credentialRequest.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        Anoncreds.proverStoreCredential(
                wallet, null, credentialRequestMetadataJson, credentialJson, credDefJson, revRegDefJson
        ).get()
    }

    /**
     * Creates proof for provided proof request
     *
     * @param proofRequest              proof request created by verifier
     * @param masterSecretId            <optional> master secret id
     *
     * @return                          proof and all reliable data
     */
    fun createProof(proofRequest: ProofRequest, masterSecretId: String = defaultMasterSecretId): ProofInfo {
        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val proverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofRequestJson).get()
        val requiredCredentialsForProof = SerializationUtils.jSONToAny<ProofRequestCredentials>(proverGetCredsForProofReq)

        val requiredAttributes = requiredCredentialsForProof.attributes.values.flatten()
        val proofRequestAttributes = proofRequest.requestedAttributes
        val attrProofData = parseProofData(proofRequestAttributes, requiredAttributes, proofRequest.nonRevoked)

        val requiredPredicates = requiredCredentialsForProof.predicates.values.flatten()
        val proofRequestPredicates = proofRequest.requestedPredicates
        val predProofData = parseProofData(proofRequestPredicates, requiredPredicates, proofRequest.nonRevoked)

        val requestedAttributes = mutableMapOf<String, RequestedAttributeInfo>()
        attrProofData
                .forEach { proofData ->
                    proofData.referentCredentials.forEach { credential ->
                        requestedAttributes[credential.key] = RequestedAttributeInfo(credential.credentialUUID, true, proofData.revState?.timestamp)
                    }
                }

        val requestedPredicates = mutableMapOf<String, RequestedPredicateInfo>()
        predProofData
                .forEach { proofData ->
                    proofData.referentCredentials.forEach { credential ->
                        requestedPredicates[credential.key] = RequestedPredicateInfo(credential.credentialUUID, proofData.revState?.timestamp)
                    }
                }

        val requestedCredentials = RequestedCredentials(requestedAttributes, requestedPredicates)

        val allSchemas = (attrProofData + predProofData)
                .map { it.schemaId }
                .distinct()
                .map {
                    ledgerService.retrieveSchema(it)
                            ?: throw IndySchemaNotFoundException(it, "Create proof has been failed")
                }

        val allCredentialDefs = (attrProofData + predProofData)
                .map { it.credDefId }
                .distinct()
                .map {
                    ledgerService.retrieveCredentialDefinition(it)
                            ?: throw IndyCredentialDefinitionNotFoundException(it, "Create proof has been failed")
                }

        val allRevStates = (attrProofData + predProofData)
                .map {
                    it.revState
                }

        val usedSchemas = allSchemas.associate { it.id to it }
        val usedCredentialDefs = allCredentialDefs.associate { it.id to it }
        val usedRevocationStates = allRevStates
                .filter { it != null }
                .associate {
                    val stateByTimestamp = hashMapOf<Long, RevocationState>()
                    stateByTimestamp[it!!.timestamp] = it

                    it.revocationRegistryId!! to stateByTimestamp
                }

        val requestedCredentialsJson = SerializationUtils.anyToJSON(requestedCredentials)
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)
        val usedCredentialDefsJson = SerializationUtils.anyToJSON(usedCredentialDefs)
        val usedRevStatesJson = SerializationUtils.anyToJSON(usedRevocationStates)

        val proverProof = Anoncreds.proverCreateProof(
                wallet,
                proofRequestJson,
                requestedCredentialsJson,
                masterSecretId,
                usedSchemasJson,
                usedCredentialDefsJson,
                usedRevStatesJson
        ).get()

        val proof = SerializationUtils.jSONToAny<ParsedProof>(proverProof)

        return ProofInfo(proof)
    }

    /**
     * Shortcut to [IndyUser.getDataUsedInProof]
     */
    fun getDataUsedInProof(proofRequest: ProofRequest, proof: ProofInfo) = IndyUser.getDataUsedInProof(did, pool, proofRequest, proof)

    /**
     * Shortcut to [IndyUser.verifyProof]
     */
    fun verifyProof(proofRequest: ProofRequest, proof: ProofInfo, usedData: DataUsedInProofJson) = IndyUser.verifyProof(proofRequest, proof, usedData)

    /**
     * Retrieves schema from ledger
     *
     * @param id                schema id
     *
     * @return                  schema or null if schema doesn't exist in ledger
     */
    fun retrieveSchemaById(id: String) = ledgerService.retrieveSchema(id)

    /**
     * Retrieves schema from ledger
     *
     * @param name              schema name
     * @param version           schema version
     *
     * @return                  schema or null if schema doesn't exist in ledger
     */
    fun retrieveSchema(name: String, version: String): Schema? {
        val schemaId = IndyUser.buildSchemaId(did, name, version)
        return retrieveSchemaById(schemaId)
    }

    /**
     * Check if schema exist on ledger
     *
     * @param name              schema name
     * @param version           schema version
     *
     * @return                  true if exist otherwise false
     */
    fun isSchemaExist(name: String, version: String): Boolean {
        return (null != retrieveSchema(name, version))
    }

    /**
     * Retrieves credential definition from ledger by schema Id
     *
     * @param id                schema id
     *
     * @return                  credential definition or null if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinitionBySchemaId(id: String): CredentialDefinition? {
        val schema = retrieveSchemaById(id)
                ?: throw IndySchemaNotFoundException(id, "Indy ledger does't have proper states")

        val credentialDefinitionId = IndyUser.buildCredentialDefinitionId(did, schema.seqNo!!)
        return ledgerService.retrieveCredentialDefinition(credentialDefinitionId)
    }

    /**
     * Retrieves credential definition from ledger
     *
     * @param id                credential definition id
     *
     * @return                  credential definition or null if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinitionById(id: String) =
            ledgerService.retrieveCredentialDefinition(id)

    /**
     * Check if credential definition exist on ledger
     *
     * @param schemaId          schema id
     *
     * @return                  true if exist otherwise false
     */
    fun isCredentialDefinitionExist(schemaId: String): Boolean {
        return (retrieveCredentialDefinitionBySchemaId(schemaId) != null)
    }

    /**
     * Retrieves revocation registry entry from ledger
     *
     * @param id                revocation registry definition id
     * @param timestamp         time moment of revocation registry state
     *
     * @return                  revocation registry entry or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryEntry(id: String, timestamp: Long) = ledgerService.retrieveRevocationRegistryEntry(id, timestamp)

    /**
     * Same as [retrieveRevocationRegistryEntry] but finds any non-revoked state in [interval]
     *
     * @param id                revocation registry definition id
     * @param interval          time interval of credential non-revocation
     *
     * @return                  revocation registry delta or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryDelta(id: String, interval: Interval) = ledgerService.retrieveRevocationRegistryDelta(id, interval)

    /**
     * Retrieves revocation registry definition from ledger
     *
     * @param id                revocation registry definition id
     *
     * @return                  revocation registry definition or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryDefinition(id: String) = ledgerService.retrieveRevocationRegistryDefinition(id)

    private data class ProofDataEntry(
            val schemaId: String,
            val credDefId: String,
            val referentCredentials: List<ReferentCredential>,
            val revState: RevocationState?
    )

    private var cachedTailsHandler: BlobStorageHandler? = null
    private fun getTailsHandler(): BlobStorageHandler {
        if (cachedTailsHandler == null) {
            val tailsConfig = getTailsConfig()

            val reader = BlobStorageReader.openReader("default", tailsConfig).get()
            val writer = BlobStorageWriter.openWriter("default", tailsConfig).get()

            cachedTailsHandler = BlobStorageHandler(reader, writer)
        }

        return cachedTailsHandler!!
    }

    private fun parseProofData(
            collectionFromRequest: Map<String, AbstractCredentialReference>,
            collectionFromCreds: List<CredentialReferenceInfo>,
            nonRevoked: Interval?
    ): List<ProofDataEntry> {

        return collectionFromCreds.map { attribute ->
            val schemaId = attribute.credentialInfo.schemaId
            val credDefId = attribute.credentialInfo.credentialDefinitionId

            val keys = collectionFromRequest.entries
                    .filter { it.value.schemaId == attribute.credentialInfo.schemaId }
                    .map { it.key }
            val reference = attribute.credentialInfo.referent
            val referentCredentials = keys.map { ReferentCredential(it, reference) }

            val credRevId = attribute.credentialInfo.credentialRevocationId
            val revRegId = attribute.credentialInfo.revocationRegistryId

            if (nonRevoked == null || credRevId == null || revRegId == null) {
                return@map ProofDataEntry(schemaId, credDefId, referentCredentials, null)
            }

            val revState = getRevocationState(credRevId, revRegId, nonRevoked)
            return@map ProofDataEntry(schemaId, credDefId, referentCredentials, revState)
        }

    }

    private fun getRevocationState(credRevId: String, revRegDefId: String, interval: Interval): RevocationState {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegDefId)
                ?: throw IndyRevRegNotFoundException(revRegDefId, "Get revocation state has been failed")
        val revRegDefJson = SerializationUtils.anyToJSON(revRegDef)

        val response = ledgerService.retrieveRevocationRegistryDelta(revRegDefId, interval)
                ?: throw IndyRevDeltaNotFoundException(revRegDefId, "Interval is $interval")
        val (timestamp, revRegDelta) = response
        val revRegDeltaJson = SerializationUtils.anyToJSON(revRegDelta)

        val revStateJson = Anoncreds.createRevocationState(
                tailsReaderHandle, revRegDefJson, revRegDeltaJson, timestamp, credRevId
        ).get()

        val revState = SerializationUtils.jSONToAny<RevocationState>(revStateJson)
        revState.revocationRegistryId = revRegDefId

        return revState
    }
}