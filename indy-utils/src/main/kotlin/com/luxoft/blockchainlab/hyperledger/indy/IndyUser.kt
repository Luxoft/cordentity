package com.luxoft.blockchainlab.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonIgnore
import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils.getIndyHomePath
import com.luxoft.blockchainlab.hyperledger.indy.utils.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
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
        const val REV_REG_TYPE = "CL_ACCUM"
        const val TAG = "TAG_1"
        const val REV_TAG = "REV_TAG_1"
        private const val ISSUANCE_ON_DEMAND = "ISSUANCE_ON_DEMAND"
        private const val EMPTY_OBJECT = "{}"

        fun buildSchemaId(did: String, name: String, version: String): String = "$did:2:$name:$version"
        fun buildCredentialDefinitionId(did: String, schemaSeqNo: Int): String = "$did:3:$SIGNATURE_TYPE:$schemaSeqNo:$TAG"
        fun buildRevocationRegistryDefinitionId(did: String, credDefId: String): String = "$did:4:$credDefId:$REV_REG_TYPE:$REV_TAG"

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
                    proofRequestJson, proofJson, usedData.schemas, usedData.claimDefs, usedData.revRegDefs, usedData.revRegs
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

            val usedClaimDefs = proof.proofData.identifiers
                    .map { it.credDefId }
                    .distinct()
                    .map {
                        LedgerService.retrieveCredentialDefinition(did, pool, it)
                                ?: throw RuntimeException("Credential definition $it doesn't exist in ledger")
                    }
                    .associate { it.id to it }
            val usedClaimDefsJson = SerializationUtils.anyToJSON(usedClaimDefs)

            val (revRegDefsJson, revRegDeltasJson) = if (proofRequest.nonRevoked != null) {
                val revRegDefs = proof.proofData.identifiers
                        .map { it.revRegId!! }
                        .distinct()
                        .map {
                            LedgerService.retrieveRevocationRegistryDefinition(did, pool, it)
                                    ?: throw RuntimeException("Revocation registry definition $it doesn't exist in ledger")
                        }
                        .associate { it.id to it }

                val revRegDeltas = proof.proofData.identifiers
                        .map { Pair(it.revRegId!!, it.timestamp!!) }
                        .distinct()
                        .associate { (revRegId, timestamp) ->
                            val response = LedgerService.retrieveRevocationRegistryEntry(did, pool, revRegId, timestamp)
                                    ?: throw RuntimeException("Revocation registry for definition $revRegId at timestamp $timestamp doesn't exist in ledger")

                            val (tmstmp, revReg) = response
                            val map = hashMapOf<Int, RevocationRegistryEntry>()
                            map[tmstmp] = revReg

                            revRegId to map
                        }

                Pair(SerializationUtils.anyToJSON(revRegDefs), SerializationUtils.anyToJSON(revRegDeltas))
            } else Pair(EMPTY_OBJECT, EMPTY_OBJECT)

            return DataUsedInProofJson(usedSchemasJson, usedClaimDefsJson, revRegDefsJson, revRegDeltasJson)
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
                attributes: List<CredFieldRef>,
                predicates: List<CredPredicate>,
                nonRevoked: Interval? = null
        ): ProofRequest {

            val requestedAttributes = attributes
                    .withIndex()
                    .associate { attr ->
                        attr.value.fieldName to ClaimFieldReference(
                                attr.value.fieldName,
                                attr.value.schemaId
                        )
                    }

            val requestedPredicates = predicates
                    .withIndex()
                    .associate { predicate ->
                        predicate.value.fieldRef.fieldName to ClaimPredicateReference(
                                predicate.value.fieldRef.fieldName,
                                predicate.value.type,
                                predicate.value.value,
                                predicate.value.fieldRef.schemaId
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
     * @param name              new schema name
     * @param version           schema version (???)
     * @param attributes        schema attributes
     *
     * @return                  created schema
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
     * Creates claim definition and stores it to ledger if not exists, else restores claim definition from ledger
     *
     * @param schemaId              id of schema to create claim definition for
     * @param enableRevocation      whether enable or disable revocation for this credential definition
     *                              (hint) turn this on by default, but just don't revoke claims
     *
     * @return                      created credential definition
     */
    fun createClaimDefinition(schemaId: String, enableRevocation: Boolean): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId)
                ?: throw RuntimeException("Schema with id: $schemaId doesn't exist in ledger")
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
     * Creates revocation registry for claim definition if there's no one in ledger
     * (usable only for those claim definition for which enableRevocation = true)
     *
     * @param credDefId                 claim definition id
     * @param maxCredentialNumber       maximum number of claims which can be issued for this claim definition
     *                                  (example) driver agency can produce only 1000 driver licences per year
     *
     * @return                          created
     */
    fun createRevocationRegistry(credDefId: String, maxCredentialNumber: Int = 5): RevocationRegistryInfo {
        val revRegDefConfig = RevocationRegistryConfig(ISSUANCE_ON_DEMAND, maxCredentialNumber)
        val revRegDefConfigJson = SerializationUtils.anyToJSON(revRegDefConfig)
        val tailsWriter = getTailsHandler().writer

        val revRegId = buildRevocationRegistryDefinitionId(did, credDefId)
        val definitionFromLedger = ledgerService.retrieveRevocationRegistryDefinition(revRegId)

        if (definitionFromLedger == null) {
            val createRevRegResult =
                    Anoncreds.issuerCreateAndStoreRevocReg(
                            wallet, did, null, REV_TAG, credDefId, revRegDefConfigJson, tailsWriter
                    ).get()

            val definition = SerializationUtils.jSONToAny<RevocationRegistryDefinition>(createRevRegResult.revRegDefJson)
            val entry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createRevRegResult.revRegEntryJson)

            ledgerService.storeRevocationRegistryDefinition(definition)
            ledgerService.storeRevocationRegistryEntry(entry, definition.id, definition.revDefType)

            return RevocationRegistryInfo(definition, entry)
        }

        val entryFromLedger = ledgerService.retrieveRevocationRegistryEntry(revRegId, Timestamp.now())
                ?: throw RuntimeException("Unable to get revocation registry entry of existing definition $revRegId from ledger")

        return RevocationRegistryInfo(definitionFromLedger, entryFromLedger.second)
    }

    /**
     * Creates claim offer
     *
     * @param credDefId             claim definition id
     *
     * @return                      created claim offer
     */
    fun createClaimOffer(credDefId: String): ClaimOffer {
        val credOfferJson = Anoncreds.issuerCreateCredentialOffer(wallet, credDefId).get()

        return SerializationUtils.jSONToAny(credOfferJson)
    }

    /**
     * Creates claim request
     *
     * @param proverDid             prover's did
     * @param offer                 claim offer
     * @param masterSecretId        <optional> master secret id
     *
     * @return                      claim request and all reliable data
     */
    fun createClaimRequest(proverDid: String, offer: ClaimOffer, masterSecretId: String = defaultMasterSecretId): ClaimRequestInfo {
        val credDef = ledgerService.retrieveCredentialDefinition(offer.credDefId)
                ?: throw RuntimeException("Credential definition ${offer.credDefId} doesn't exist in ledger")

        val claimOfferJson = SerializationUtils.anyToJSON(offer)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        createMasterSecret(masterSecretId)

        val credReq = Anoncreds.proverCreateCredentialReq(
                wallet, proverDid, claimOfferJson, credDefJson, masterSecretId
        ).get()

        val claimRequest = SerializationUtils.jSONToAny<ClaimRequest>(credReq.credentialRequestJson)
        val claimRequestMetadata = SerializationUtils.jSONToAny<ClaimRequestMetadata>(credReq.credentialRequestMetadataJson)

        return ClaimRequestInfo(claimRequest, claimRequestMetadata)
    }

    /**
     * Issues claim by claim request. If revocation is enabled it will hold one of [maxCredentialNumber].
     *
     * @param claimReq              claim request and all reliable info
     * @param proposal              claim proposal
     * @param offer                 claim offer
     * @param revRegId              <optional> revocation registry definition ID
     *
     * @return                      claim and all reliable info
     */
    fun issueClaim(claimReq: ClaimRequestInfo, proposal: String, offer: ClaimOffer, revRegId: String?): ClaimInfo {
        val claimRequestJson = SerializationUtils.anyToJSON(claimReq.request)
        val claimOfferJson = SerializationUtils.anyToJSON(offer)
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

        val createClaimResult = Anoncreds.issuerCreateCredential(
                wallet,
                claimOfferJson,
                claimRequestJson,
                proposal,
                revRegId,
                tailsReaderHandle
        ).get()

        val claim = SerializationUtils.jSONToAny<Claim>(createClaimResult.credentialJson)

        if (revRegId != null) {
            val revocationRegistryDefinition = ledgerService.retrieveRevocationRegistryDefinition(revRegId)
                    ?: throw RuntimeException("Revocation registry definition $revRegId doesn't exist in ledger")

            val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createClaimResult.revocRegDeltaJson)

            ledgerService.storeRevocationRegistryEntry(revRegDelta, revRegId, revocationRegistryDefinition.revDefType)
        }

        return ClaimInfo(claim, createClaimResult.revocId, createClaimResult.revocRegDeltaJson)
    }

    /**
     * Revokes previously issued claim
     *
     * @param revRegId              revocation registry definition id
     * @param credRevId             revocation registry claim index
     */
    fun revokeClaim(revRegId: String, credRevId: String) {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle
        val revRegDeltaJson = Anoncreds.issuerRevokeCredential(wallet, tailsReaderHandle, revRegId, credRevId).get()
        val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson)
        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegId)
                ?: throw RuntimeException("Revocation registry definition $revRegId doesn't exist in ledger")

        ledgerService.storeRevocationRegistryEntry(revRegDelta, revRegId, revRegDef.revDefType)
    }

    /**
     * Stores claim in prover's wallet
     *
     * @param claimInfo             claim and all reliable data
     * @param claimReq              claim request and all reliable data
     * @param offer                 claim offer
     */
    fun receiveClaim(claimInfo: ClaimInfo, claimReq: ClaimRequestInfo, offer: ClaimOffer) {
        val revRegDefJson = if (claimInfo.claim.revRegId != null) {
            val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(claimInfo.claim.revRegId)
                    ?: throw RuntimeException("Revocation registry definition ${claimInfo.claim.revRegId} doesn't exist in ledger")

            SerializationUtils.anyToJSON(revRegDef)
        } else null

        val credDef = ledgerService.retrieveCredentialDefinition(offer.credDefId)
                ?: throw RuntimeException("Credential definition ${offer.credDefId} doesn't exist in ledger")

        val claimJson = SerializationUtils.anyToJSON(claimInfo.claim)
        val claimRequestMetadataJson = SerializationUtils.anyToJSON(claimReq.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        Anoncreds.proverStoreCredential(
                wallet, null, claimRequestMetadataJson, claimJson, credDefJson, revRegDefJson
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
        val requiredClaimsForProof = SerializationUtils.jSONToAny<ProofRequestCredentials>(proverGetCredsForProofReq)

        val requiredAttributes = requiredClaimsForProof.attrs.values.flatten()
        val proofRequestAttributes = proofRequest.requestedAttributes
        val attrProofData = parseProofData(proofRequestAttributes, requiredAttributes, proofRequest.nonRevoked)

        val requiredPredicates = requiredClaimsForProof.predicates.values.flatten()
        val proofRequestPredicates = proofRequest.requestedPredicates
        val predProofData = parseProofData(proofRequestPredicates, requiredPredicates, proofRequest.nonRevoked)

        val requestedAttributes = mutableMapOf<String, RequestedAttributeInfo>()
        attrProofData
                .forEach { proofData ->
                    proofData.referentClaims.forEach { claim ->
                        requestedAttributes[claim.key] = RequestedAttributeInfo(claim.claimUuid, true, proofData.revState?.timestamp)
                    }
                }

        val requestedPredicates = mutableMapOf<String, RequestedPredicateInfo>()
        predProofData
                .forEach { proofData ->
                    proofData.referentClaims.forEach { claim ->
                        requestedPredicates[claim.key] = RequestedPredicateInfo(claim.claimUuid, proofData.revState?.timestamp)
                    }
                }

        val requestedCredentials = RequestedCredentials(requestedAttributes, requestedPredicates)

        val allSchemas = (attrProofData + predProofData)
                .map { it.schemaId }
                .distinct()
                .map {
                    ledgerService.retrieveSchema(it)
                            ?: throw RuntimeException("Schema $it doesn't exist in ledger")
                }

        val allClaimDefs = (attrProofData + predProofData)
                .map { it.credDefId }
                .distinct()
                .map {
                    ledgerService.retrieveCredentialDefinition(it)
                            ?: throw RuntimeException("Credential definition $it doesn't exist in ledger")
                }

        val allRevStates = (attrProofData + predProofData)
                .map {
                    it.revState
                }

        val usedSchemas = allSchemas.associate { it.id to it }
        val usedClaimDefs = allClaimDefs.associate { it.id to it }
        val usedRevocationStates = allRevStates
                .filter { it != null }
                .associate {
                    val stateByTimestamp = hashMapOf<Int, RevocationState>()
                    stateByTimestamp[it!!.timestamp] = it

                    it.revRegId!! to stateByTimestamp
                }

        val requestedCredentialsJson = SerializationUtils.anyToJSON(requestedCredentials)
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)
        val usedClaimDefsJson = SerializationUtils.anyToJSON(usedClaimDefs)
        val usedRevStatesJson = SerializationUtils.anyToJSON(usedRevocationStates)

        val proverProof = Anoncreds.proverCreateProof(
                wallet,
                proofRequestJson,
                requestedCredentialsJson,
                masterSecretId,
                usedSchemasJson,
                usedClaimDefsJson,
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
     * @param schemaId          schema id
     *
     * @return                  schema or null if schema doesn't exist in ledger
     */
    fun retrieveSchemaById(schemaId: String) = ledgerService.retrieveSchema(schemaId)

    /**
     * Retrieves schema from ledger
     *
     * @param name          schema name
     * @param version       schema version
     *
     * @return              schema or null if schema doesn't exist in ledger
     */
    fun retrieveSchema(name: String, version: String): Schema? {
        val schemaId = IndyUser.buildSchemaId(did, name, version)
        return retrieveSchemaById(schemaId)
    }

    /**
     * Check if schema exist on ledger
     *
     * @param name          schema name
     * @param version       schema version
     *
     * @return              true if exist otherwise false
     */
    fun isSchemaExist(name: String, version: String): Boolean {
        return (null != retrieveSchema(name, version))
    }

    /**
     * Retrieves claim definition from ledger by schema Id
     *
     * @param schemaId        schema id
     *
     * @return                claim definition or null if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinitionBySchemaId(schemaId: String): CredentialDefinition? {
        val schema = retrieveSchemaById(schemaId)
                ?: throw IndySchemaNotFoundException(schemaId, "Indy ledger does't have proper states")

        val credentialDefinitionId = IndyUser.buildCredentialDefinitionId(did, schema.seqNo!!)
        return ledgerService.retrieveCredentialDefinition(credentialDefinitionId)
    }

    /**
     * Retrieves claim definition from ledger
     *
     * @param claimDefId        claim definition id
     *
     * @return                  claim definition or null if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinitionById(credentialDefinitionId: String) =
            ledgerService.retrieveCredentialDefinition(credentialDefinitionId)

    /**
     * Check if credential definition exist on ledger
     *
     * @param schemaId      schema id
     *
     * @return              true if exist otherwise false
     */
    fun isCredentialDefinitionExist(schemaId: String): Boolean {
        return (retrieveCredentialDefinitionBySchemaId(schemaId) != null)
    }

    /**
     * Retrieves revocation registry entry from ledger
     *
     * @param revRegId          revocation registry definition id
     * @param timestamp         time moment of revocation registry state
     *
     * @return                  revocation registry entry or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryEntry(revRegId: String, timestamp: Int) = ledgerService.retrieveRevocationRegistryEntry(revRegId, timestamp)

    /**
     * Same as [retrieveRevocationRegistryEntry] but finds any non-revoked state in [interval]
     *
     * @param revRegId          revocation registry definition id
     * @param interval          time interval of claim non-revocation
     *
     * @return                  revocation registry delta or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryDelta(revRegId: String, interval: Interval) = ledgerService.retrieveRevocationRegistryDelta(revRegId, interval)

    /**
     * Retrieves revocation registry definition from ledger
     *
     * @param revRegId          revocation registry definition id
     *
     * @return                  revocation registry definition or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryDefinition(revRegId: String) = ledgerService.retrieveRevocationRegistryDefinition(revRegId)

    private data class ProofDataEntry(
            val schemaId: String,
            val credDefId: String,
            val referentClaims: List<ReferentClaim>,
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
            collectionFromRequest: Map<String, AbstractClaimReference>,
            collectionFromCreds: List<ClaimReferenceInfo>,
            nonRevoked: Interval?
    ): List<ProofDataEntry> {

        return collectionFromCreds.map { attribute ->
            val schemaId = attribute.credInfo.schemaId
            val credDefId = attribute.credInfo.credDefId

            val keys = collectionFromRequest.entries
                    .filter { it.value.schemaId == attribute.credInfo.schemaId }
                    .map { it.key }
            val reference = attribute.credInfo.referent
            val referentClaims = keys.map { ReferentClaim(it, reference) }

            val credRevId = attribute.credInfo.credRevId
            val revRegId = attribute.credInfo.revRegId

            if (nonRevoked == null || credRevId == null || revRegId == null) {
                return@map ProofDataEntry(schemaId, credDefId, referentClaims, null)
            }

            val revState = getRevocationState(credRevId, revRegId, nonRevoked)
            return@map ProofDataEntry(schemaId, credDefId, referentClaims, revState)
        }

    }

    private fun getRevocationState(credRevId: String, revRegDefId: String, interval: Interval): RevocationState {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegDefId)
                ?: throw RuntimeException("Revocation registry definition $revRegDefId doesn't exist in ledger")
        val revRegDefJson = SerializationUtils.anyToJSON(revRegDef)

        val response = ledgerService.retrieveRevocationRegistryDelta(revRegDefId, interval)
                ?: throw RuntimeException("Revocation registry delta for definition $revRegDefId at interval $interval doesn't exist in ledger")
        val (timestamp, revRegDelta) = response
        val revRegDeltaJson = SerializationUtils.anyToJSON(revRegDelta)

        val revStateJson = Anoncreds.createRevocationState(
                tailsReaderHandle, revRegDefJson, revRegDeltaJson, timestamp, credRevId
        ).get()

        val revState = SerializationUtils.jSONToAny<RevocationState>(revStateJson)
        revState.revRegId = revRegDefId

        return revState
    }
}