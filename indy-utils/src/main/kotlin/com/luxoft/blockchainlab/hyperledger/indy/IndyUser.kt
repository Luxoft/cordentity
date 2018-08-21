package com.luxoft.blockchainlab.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonIgnore
import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils.getCurrentUnixEpochTime
import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils.getIndyHomePath
import com.luxoft.blockchainlab.hyperledger.indy.utils.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import net.corda.core.serialization.CordaSerializable
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.CredDefAlreadyExistsException
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
 * The central class that incapsulates Indy SDK calls and keeps the corresponding state.
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
        fun getIdentityRecord() = "{\"did\":\"$did\", \"verkey\":\"$verkey\"}"
    }

    private val logger = LoggerFactory.getLogger(IndyUser::class.java.name)

    val defaultMasterSecretId = "master"
    val did: String
    val verkey: String

    protected val wallet: Wallet
    protected val pool: Pool

    private var ledgerService: LedgerService

    constructor(wallet: Wallet, did: String? = null, didConfig: String = "{}") :
            this(PoolManager.getInstance().pool, wallet, did, didConfig)

    constructor(pool: Pool, wallet: Wallet, did: String?, didConfig: String = "{}") {

        this.pool = pool
        this.wallet = wallet

        var _did: String
        var _verkey: String

        if (did != null) {
            try {
                _did = did
                _verkey = Did.keyForLocalDid(wallet, did).get()

            } catch (ex: ExecutionException) {
                if (getRootCause(ex) !is WalletItemNotFoundException) throw ex

                val didResult = Did.createAndStoreMyDid(wallet, didConfig).get()
                _did = didResult.did
                _verkey = didResult.verkey
            }
        } else {
            val didResult = Did.createAndStoreMyDid(wallet, didConfig).get()
            _did = didResult.did
            _verkey = didResult.verkey
        }

        this.did = _did
        this.verkey = _verkey

        ledgerService = LedgerService(this.did, this.wallet, this.pool)
    }

    fun close() {
        wallet.closeWallet().get()
    }

    fun getIdentity(did: String): IdentityDetails {
        return IdentityDetails(did, Did.keyForDid(pool, wallet, did).get(), null, null)
    }

    fun getIdentity() = getIdentity(did)

    fun addKnownIdentities(identityDetails: IdentityDetails) {
        Did.storeTheirDid(wallet, identityDetails.getIdentityRecord()).get()
    }

    fun setPermissionsFor(identityDetails: IdentityDetails) {
        addKnownIdentities(identityDetails)
        ledgerService.nymFor(identityDetails)
    }

    fun createMasterSecret(masterSecretId: String) {
        try {
            Anoncreds.proverCreateMasterSecret(wallet, masterSecretId).get()
        } catch (e: ExecutionException) {
            if (getRootCause(e) !is DuplicateMasterSecretNameException) throw e

            logger.debug("MasterSecret already exists, who cares, continuing")
        }
    }

    fun createSessionDid(identityRecord: IdentityDetails): String {
        if (!Pairwise.isPairwiseExists(wallet, identityRecord.did).get()) {
            addKnownIdentities(identityRecord)
            val sessionDid = Did.createAndStoreMyDid(wallet, "{}").get().did
            Pairwise.createPairwise(wallet, identityRecord.did, sessionDid, "").get()
        }

        val pairwiseJson = Pairwise.getPairwise(wallet, identityRecord.did).get()
        val pairwise = SerializationUtils.jSONToAny<ParsedPairwise>(pairwiseJson)
                ?: throw RuntimeException("Unable to parse pairwise from json")

        return pairwise.myDid
    }

    fun createSchema(name: String, version: String, attributes: List<String>): Schema {
        val attrStr = attributes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attrStr).get()
        val schema = SerializationUtils.jSONToAny<Schema>(schemaInfo.schemaJson)
                ?: throw RuntimeException("Unable to parse schema from json")

        ledgerService.storeSchema(schema)

        return schema
    }

    fun createClaimDef(schemaId: String): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId)
        val schemaJson = SerializationUtils.anyToJSON(schema)

        // Let's hope this format is correct and stays unchanged
        val supposedCredDefId = "$did:3:$SIGNATURE_TYPE:${schema.seqNo}:$TAG"
        val credDefConfigJson = """{"support_revocation":true}"""

        return try {
            val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, schemaJson, TAG, SIGNATURE_TYPE, credDefConfigJson).get()
            val credDef = SerializationUtils.jSONToAny<CredentialDefinition>(credDefInfo.credDefJson)
                    ?: throw RuntimeException("Unable to parse credential definition from json")

            ledgerService.storeCredentialDefinition(credDef)

            credDef
        } catch (e: Exception) {
            if (e.cause !is CredDefAlreadyExistsException) throw e.cause ?: e

            // TODO: this have to be removed when IndyRegistry will be implemented
            logger.error("Credential Definiton for $schemaId already exist.")

            ledgerService.retrieveCredentialDefinition(supposedCredDefId)
        }
    }

    fun createRevocationRegistry(credentialDefinition: CredentialDefinition): RevocationRegistryInfo {
        val revRegDefConfig = RevocationRegistryConfig("ISSUANCE_ON_DEMAND", 10000)
        val revRegDefConfigJson = SerializationUtils.anyToJSON(revRegDefConfig)
        val tailsWriter = getTailsHandler().writer

        val createRevRegResult =
                Anoncreds.issuerCreateAndStoreRevocReg(
                        wallet, did, null, REV_TAG, credentialDefinition.id, revRegDefConfigJson, tailsWriter
                ).get()

        val definition = SerializationUtils.jSONToAny<RevocationRegistryDefinition>(createRevRegResult.revRegDefJson)
                ?: throw RuntimeException("Unable to parse revocation registry definition from json")
        val entry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createRevRegResult.revRegEntryJson)
                ?: throw RuntimeException("Unable to parse revocation registry entry from json")

        ledgerService.storeRevocationRegistryDefinition(definition)
        ledgerService.storeRevocationRegistryEntry(entry, definition.id, definition.revDefType)

        return RevocationRegistryInfo(definition, entry)
    }

    data class BlobStorageHandler(val reader: BlobStorageReader, val writer: BlobStorageWriter)

    private var cachedTailsHandler: BlobStorageHandler? = null
    private fun getTailsHandler(): BlobStorageHandler {
        if (cachedTailsHandler == null) {
            val tailsWriterConfig = """{"base_dir":"${getIndyHomePath("tails")}","uri_pattern":""}""".replace('\\', '/')

            val reader = BlobStorageReader.openReader("default", tailsWriterConfig).get()
            val writer = BlobStorageWriter.openWriter("default", tailsWriterConfig).get()

            cachedTailsHandler = BlobStorageHandler(reader, writer)
        }

        return cachedTailsHandler!!
    }

    fun createClaimOffer(credDefId: String): ClaimOffer {
        val credOfferJson = Anoncreds.issuerCreateCredentialOffer(wallet, credDefId).get()

        return SerializationUtils.jSONToAny<ClaimOffer>(credOfferJson)
                ?: throw RuntimeException("Unable to parse claim offer from json")
    }

    fun createClaimReq(sessionDid: String, offer: ClaimOffer, masterSecretId: String = defaultMasterSecretId): ClaimRequestInfo {
        val credDef = ledgerService.retrieveCredentialDefinition(offer.credDefId)

        val claimOfferJson = SerializationUtils.anyToJSON(offer)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        createMasterSecret(masterSecretId)

        val credReq = Anoncreds.proverCreateCredentialReq(
                wallet, sessionDid, claimOfferJson, credDefJson, masterSecretId
        ).get()

        val claimRequest = SerializationUtils.jSONToAny<ClaimRequest>(credReq.credentialRequestJson)
                ?: throw RuntimeException("Unable to parse claim request from json")

        val claimRequestMetadata = SerializationUtils.jSONToAny<ClaimRequestMetadata>(credReq.credentialRequestMetadataJson)
                ?: throw RuntimeException("Unable to parse claim request metadata from json")

        return ClaimRequestInfo(claimRequest, claimRequestMetadata)
    }

    fun issueClaim(claimReq: ClaimRequestInfo, proposal: String, offer: ClaimOffer, revRegId: String): ClaimInfo {
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
                ?: throw RuntimeException("Unable to parse claim from a given credential json")

        return ClaimInfo(claim, createClaimResult.revocId, createClaimResult.revocRegDeltaJson)
    }

    fun receiveClaim(claimInfo: ClaimInfo, claimReq: ClaimRequestInfo, offer: ClaimOffer, revRegDefId: String) {
        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegDefId)
        val revRegDefJson = SerializationUtils.anyToJSON(revRegDef)

        val credDef = ledgerService.retrieveCredentialDefinition(offer.credDefId)

        val claimJson = SerializationUtils.anyToJSON(claimInfo.claim)
        val claimRequestMetadataJson = SerializationUtils.anyToJSON(claimReq.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        Anoncreds.proverStoreCredential(
                wallet, null, claimRequestMetadataJson, claimJson, credDefJson, revRegDefJson
        ).get()
    }

    /**
     * @brief - generate proof request to convince that specific fields are valid
     * @param attributes - list of attributes from schema to request for proof
     * @param predicates - list of predicates/assumptions that should be proofed by other side
     *
     * @note Sample: I'm Alex and my age is more that 18.
     * Predicate here: value '18', field 'age' (great then 18)
     * Arguments: name 'Alex'
     */
    fun createProofReq(attributes: List<CredFieldRef>, predicates: List<CredPredicate>): ProofRequest {

        // 1. Add attributes
        val requestedAttributes = attributes
                .withIndex()
                .associate { attr ->
                    "attr${attr.index}_referent" to ClaimFieldReference(
                            attr.value.fieldName,
                            attr.value.schemaId,
                            attr.value.credDefId
                    )
                }

        // 2. Add predicates
        val requestedPredicates = predicates
                .withIndex()
                .associate { predicate ->
                    "predicate${predicate.index}_referent" to ClaimPredicateReference(
                            predicate.value.fieldRef.fieldName,
                            predicate.value.type,
                            predicate.value.value,
                            predicate.value.fieldRef.schemaId,
                            predicate.value.fieldRef.credDefId
                    )
                }

        return ProofRequest(
                "0.1",
                "proof_req_1",
                "123432421212",
                requestedAttributes,
                requestedPredicates
        )
    }

    fun createProof(proofRequest: ProofRequest, masterSecretId: String = defaultMasterSecretId): ProofInfo {
        logger.debug("proofReq = $proofRequest")

        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val proverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofRequestJson).get()
        val requiredClaimsForProof = SerializationUtils.jSONToAny<ProofRequestCredentials>(proverGetCredsForProofReq)
                ?: throw RuntimeException("Unable to parse credentials for proof request from json")

        logger.debug("requiredClaimsForProof = $requiredClaimsForProof")

        val (proofAttrs, proofPreds) = generateProofData(proofRequest, requiredClaimsForProof)
        val requestedAttributes = proofAttrs.claims.associate { it.key to RequestedAttributeInfo(it.claimUuid) }
        val requestedPredicates = proofPreds.claims.associate { it.key to RequestedPredicateInfo(it.claimUuid) }
        val requestedCredentials = RequestedCredentials(requestedAttributes, requestedPredicates)

        val allSchemas = (proofAttrs.schemaIds + proofPreds.schemaIds).map { ledgerService.retrieveSchema(it) }
        val allClaimDefs = (proofAttrs.credDefIds + proofPreds.credDefIds).map { ledgerService.retrieveCredentialDefinition(it) }
        val allRevocationStates = (proofAttrs.revIds + proofPreds.revIds)
                .map { Pair(it.revRegId, getRevocationState(it.credRevId, it.revRegId)) }

        // {
        //  "witness":{
        //      "omega":"true 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        //  },
        //  "rev_reg":{
        //      "accum":"true 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        //  },
        //  "timestamp":1534781414
        // }

        val usedSchemas = allSchemas.associate { it.id to it }
        val usedClaimDefs = allClaimDefs.associate { it.id to it }
        val usedRevocationStates = allRevocationStates
                .associate { (revRegId, revocationState) ->
                    val stateByTimestamp = hashMapOf<Int, RevocationState>()
                    stateByTimestamp[revocationState.requestedTimestamp] = revocationState

                    revRegId to stateByTimestamp
                }

        val requestedCredentialsJson = SerializationUtils.anyToJSON(requestedCredentials)
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)
        val usedClaimDefsJson = SerializationUtils.anyToJSON(usedClaimDefs)
        val usedRevocationStatesJson = SerializationUtils.anyToJSON(usedRevocationStates)

        /**
         * {
         *  "V4SGRU86Z58d6TV7PBUe6f:4:V4SGRU86Z58d6TV7PBUe6f:3:CL:11:TAG_1:CL_ACCUM:REV_TAG_1":{
         *      "1534843459":{
         *          "witness":{"omega":"true 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"},
         *          "rev_reg":{"accum":"true 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"},
         *          "timestamp":1534843459
         *      }
         *  },
         *  "V4SGRU86Z58d6TV7PBUe6f:4:V4SGRU86Z58d6TV7PBUe6f:3:CL:13:TAG_1:CL_ACCUM:REV_TAG_1":{
         *      "1534843459":{
         *          "witness":{"omega":"true 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"},
         *          "rev_reg":{"accum":"true 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"},
         *          "timestamp":1534843459
         *      }
         *  }
         * }
         */

        // 4. issue proof
        val proverProof = Anoncreds.proverCreateProof(
                wallet,
                proofRequestJson,
                requestedCredentialsJson,
                masterSecretId,
                usedSchemasJson,
                usedClaimDefsJson,
                usedRevocationStatesJson
        ).get()

        val proof = SerializationUtils.jSONToAny<ParsedProof>(proverProof)
                ?: throw RuntimeException("Unable to parse proof from json")

        return ProofInfo(proof, usedSchemas, usedClaimDefs)
    }

    data class ReferentClaim(val key: String, val claimUuid: String)
    data class RevocationIds(val credRevId: String, val revRegId: String)
    data class ProofData(val claims: List<ReferentClaim>, val schemaIds: Set<String>, val credDefIds: Set<String>, val revIds: Set<RevocationIds>)

    private fun generateProofData(proofRequest: ProofRequest, requiredClaimsForProof: ProofRequestCredentials): Pair<ProofData, ProofData> {
        val attrSchemaIds = mutableSetOf<String>()
        val attrCredDefIds = mutableSetOf<String>()
        val attrRevIds = mutableSetOf<RevocationIds>()

        val attributeReferentClaimsNullable = proofRequest.requestedAttributes.entries
                .map { attribute ->
                    val claimReferences = requiredClaimsForProof.attrs.values
                            .map { it.map { it.credInfo } }
                            .flatten()

                    val claim = claimReferences.firstOrNull { it.schemaId == attribute.value.schemaId }
                            ?: return@map null

                    attrSchemaIds.add(claim.schemaId)
                    attrCredDefIds.add(claim.credDefId)
                    if (claim.revRegId != null && claim.credRevId != null) {
                        val revocationIds = RevocationIds(claim.credRevId, claim.revRegId)
                        attrRevIds.add(revocationIds)
                    }

                    val claimUuid = claim.referent

                    ReferentClaim(attribute.key, claimUuid)
                }

        val attributeReferentClaims = listOfNotNull(*attributeReferentClaimsNullable.toTypedArray())
        val attributeProofData = ProofData(attributeReferentClaims, attrSchemaIds, attrCredDefIds, attrRevIds)

        val predSchemaIds = mutableSetOf<String>()
        val predCredDefIds = mutableSetOf<String>()
        val predRevIds = mutableSetOf<RevocationIds>()

        val predicateReferentClaimsNullable = proofRequest.requestedPredicates.entries
                .map { predicate ->
                    val claimReferences = requiredClaimsForProof.predicates.values
                            .map { it.map { it.credInfo } }
                            .flatten()

                    val claim = claimReferences.firstOrNull { it.schemaId == predicate.value.schemaId }
                            ?: return@map null

                    predSchemaIds.add(claim.schemaId)
                    predCredDefIds.add(claim.credDefId)
                    if (claim.revRegId != null && claim.credRevId != null) {
                        val revocationIds = RevocationIds(claim.credRevId, claim.revRegId)
                        predRevIds.add(revocationIds)
                    }

                    val claimUuid = claim.referent

                    ReferentClaim(predicate.key, claimUuid)
                }

        val predicateReferentClaims = listOfNotNull(*predicateReferentClaimsNullable.toTypedArray())
        val predicateProofData = ProofData(predicateReferentClaims, predSchemaIds, predCredDefIds, predRevIds)

        return Pair(attributeProofData, predicateProofData)
    }

    private fun getRevocationState(credRevId: String, revRegDefId: String, timestamp: Int = getCurrentUnixEpochTime()): RevocationState {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegDefId)
        val revRegDefJson = SerializationUtils.anyToJSON(revRegDef)

        val revRegDelta = ledgerService.retrieveRevocationRegistryDelta(revRegDefId, -1, timestamp)

        val revStateJson = Anoncreds.createRevocationState(tailsReaderHandle, revRegDefJson, revRegDelta, timestamp, credRevId).get()

        val revState = SerializationUtils.jSONToAny<RevocationState>(revStateJson)
                ?: throw RuntimeException("Unable to parse revocation state from json")
        revState.requestedTimestamp = timestamp

        return revState
    }

    companion object {
        private const val SIGNATURE_TYPE = "CL"
        private const val TAG = "TAG_1"
        private const val REV_TAG = "REV_TAG_1"

        fun verifyProof(proofReq: ProofRequest, proof: ProofInfo): Boolean {
            val proofRequestJson = SerializationUtils.anyToJSON(proofReq)
            val proofJson = SerializationUtils.anyToJSON(proof.proofData)
            val usedSchemasJson = SerializationUtils.anyToJSON(proof.usedSchemas)
            val usedClaimDefsJson = SerializationUtils.anyToJSON(proof.usedClaimDefs)

            return Anoncreds.verifierVerifyProof(
                    proofRequestJson, proofJson, usedSchemasJson, usedClaimDefsJson, "{}", "{}").get()
        }
    }

    class ArtifactDoesntExist(id: String) : IllegalArgumentException("Artifact with id ${id} doesnt exist on public ledger")
}