package com.luxoft.blockchainlab.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonIgnore
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import net.corda.core.serialization.CordaSerializable
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.CredDefAlreadyExistsException
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.blob_storage.BlobStorageWriter
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.ledger.Ledger
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
        @JsonIgnore fun getIdentityRecord() = "{\"did\":\"$did\", \"verkey\":\"$verkey\"}"
    }

    val logger = LoggerFactory.getLogger(IndyUser::class.java.name)

    val defaultMasterSecretId = "master"
    val did: String
    val verkey: String

    protected val wallet: Wallet
    protected val pool: Pool

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

        val nymRequest = Ledger.buildNymRequest(did,
                identityDetails.did,
                identityDetails.verkey,
                identityDetails.alias,
                identityDetails.role).get()

        Ledger.signAndSubmitRequest(pool, wallet, did, nymRequest).get()
    }

    fun createRevokReg(credDefId: String) {
        val tailsWriter = BlobStorageWriter.openWriter("default", "{}").get()
        Anoncreds.issuerCreateAndStoreRevocReg(wallet, did, null, TAG, credDefId, "{}", tailsWriter).get()
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

    fun createSchema(name: String, version: String, attributes: List<String>): Schema =
        try {
            val schemaId = getSchemaId(did, name, version)
            getSchema(schemaId)

        } catch(e: ArtifactDoesntExist) {
            val attrStr = attributes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

            val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attrStr).get()
            val schemaRequest = Ledger.buildSchemaRequest(did, schemaInfo.schemaJson).get()

            logger.error("Request to create new schema " +
                    "${schemaInfo.schemaId}:${schemaInfo.schemaJson}")

            errorHandler(Ledger.signAndSubmitRequest(pool, wallet, did, schemaRequest).get())
            getSchema(schemaInfo.schemaId)
        }

    fun createClaimDef(schemaId: String): CredentialDefinition {
        val schema = getSchema(schemaId)
        val schemaJson = SerializationUtils.anyToJSON(schema)

        val credDefId = getCredDefId(did, schema.seqNo!!)

        try {
            return getClaimDef(credDefId)

        } catch (e: ArtifactDoesntExist) {
            val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, schemaJson, TAG, SIGNATURE_TYPE, null).get()
            val claimDefReq = Ledger.buildCredDefRequest(did, credDefInfo.credDefJson).get()

            logger.info("Request to create new credential definition " +
                    "${credDefInfo.credDefId}:${credDefInfo.credDefJson}")

            errorHandler(Ledger.signAndSubmitRequest(pool, wallet, did, claimDefReq).get())
            return getClaimDef(credDefInfo.credDefId)

        } catch (e: Exception) {
            if (e.cause !is CredDefAlreadyExistsException) throw e.cause ?: e

            logger.error("Credential Definiton for ${schemaId} already exist ${e.message}")
            return getClaimDef(credDefId)
        }
    }

    fun createClaimOffer(credDefId: String): ClaimOffer {
        val credOfferJson = Anoncreds.issuerCreateCredentialOffer(wallet, credDefId).get()

        return SerializationUtils.jSONToAny<ClaimOffer>(credOfferJson)
                ?: throw RuntimeException("Unable to parse claim offer from json")
    }

    fun createClaimReq(sessionDid: String, offer: ClaimOffer, masterSecretId: String = defaultMasterSecretId): ClaimRequestInfo {
        val credDef = getClaimDef(offer.credDefId)

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

    fun issueClaim(claimReq: ClaimRequestInfo, proposal: String, offer: ClaimOffer): ClaimInfo {
        val claimRequestJson = SerializationUtils.anyToJSON(claimReq.request)
        val claimOfferJson = SerializationUtils.anyToJSON(offer)

        val createClaimResult = Anoncreds.issuerCreateCredential(
                wallet,
                claimOfferJson,
                claimRequestJson,
                proposal,
                null,
                -1
        ).get()

        val claim = SerializationUtils.jSONToAny<Claim>(createClaimResult.credentialJson)
                ?: throw RuntimeException("Unable to parse claim from a given credential json")

        return ClaimInfo(claim, createClaimResult.revocId, createClaimResult.revocRegDeltaJson)
    }

    fun receiveClaim(claim: Claim, claimReq: ClaimRequestInfo, offer: ClaimOffer) {
        val credDef = getClaimDef(offer.credDefId)

        val claimJson = SerializationUtils.anyToJSON(claim)
        val claimRequestMetadataJson = SerializationUtils.anyToJSON(claimReq.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        Anoncreds.proverStoreCredential(
                wallet, null, claimRequestMetadataJson, claimJson, credDefJson, null
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

        val allSchemas = (proofAttrs.schemaIds + proofPreds.schemaIds).map { schemaId -> getSchema(schemaId) }
        val allClaimDefs = (proofAttrs.credDefIds + proofPreds.credDefIds).map { credDefId -> getClaimDef(credDefId) }

        val usedSchemas = allSchemas.associate { it.id to it }
        val usedClaimDefs = allClaimDefs.associate { it.id to it }

        val requestedCredentialsJson = SerializationUtils.anyToJSON(requestedCredentials)
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)
        val usedClaimDefsJson = SerializationUtils.anyToJSON(usedClaimDefs)

        // 4. issue proof
        val proverProof = Anoncreds.proverCreateProof(
                wallet,
                proofRequestJson,
                requestedCredentialsJson,
                masterSecretId,
                usedSchemasJson,
                usedClaimDefsJson,
                "{}"
        ).get()

        val proof = SerializationUtils.jSONToAny<ParsedProof>(proverProof)
                ?: throw RuntimeException("Unable to parse proof from json")

        return ProofInfo(proof, usedSchemas, usedClaimDefs)
    }

    data class ReferentClaim(val key: String, val claimUuid: String)

    data class ProofData(val claims: List<ReferentClaim>, val schemaIds: Set<String>, val credDefIds: Set<String>)

    private fun generateProofData(proofRequest: ProofRequest, requiredClaimsForProof: ProofRequestCredentials): Pair<ProofData, ProofData> {
        val attrSchemaIds = mutableSetOf<String>()
        val attrCredDefIds = mutableSetOf<String>()

        val attributeReferentClaimsNullable = proofRequest.requestedAttributes.entries
                .map { attribute ->
                    val claimReferences = requiredClaimsForProof.attrs.values
                            .map { it.map { it.credInfo } }
                            .flatten()
                            .distinct()

                    val claim = claimReferences.firstOrNull { it.schemaId == attribute.value.schemaId }
                            ?: return@map null

                    attrSchemaIds.add(attribute.value.schemaId)
                    attrCredDefIds.add(attribute.value.credDefId)

                    val claimUuid = claim.referent

                    ReferentClaim(attribute.key, claimUuid)
                }

        val attributeReferentClaims = listOfNotNull(*attributeReferentClaimsNullable.toTypedArray())
        val attributeProofData = ProofData(attributeReferentClaims, attrSchemaIds, attrCredDefIds)

        val predSchemaIds = mutableSetOf<String>()
        val predCredDefIds = mutableSetOf<String>()

        val predicateReferentClaimsNullable = proofRequest.requestedPredicates.entries
                .map { predicate ->
                    val claimReferences = requiredClaimsForProof.predicates.values
                            .map { it.map { it.credInfo } }
                            .flatten()
                            .distinct()

                    val claim = claimReferences.firstOrNull { it.schemaId == predicate.value.schemaId }
                            ?: return@map null

                    predSchemaIds.add(predicate.value.schemaId)
                    predCredDefIds.add(predicate.value.credDefId)

                    val claimUuid = claim.referent

                    ReferentClaim(predicate.key, claimUuid)
                }

        val predicateReferentClaims = listOfNotNull(*predicateReferentClaimsNullable.toTypedArray())
        val predicateProofData = ProofData(predicateReferentClaims, predSchemaIds, predCredDefIds)

        return Pair(attributeProofData, predicateProofData)
    }

    fun getSchema(schemaId: String): Schema {
        logger.info("getting schema from public ledger: $schemaId")

        val req = Ledger.buildGetSchemaRequest(did, schemaId).get()
        return extractResult(Ledger.submitRequest(pool, req).get(), Ledger::parseGetSchemaResponse)
    }

    fun getClaimDef(credDefId: String): CredentialDefinition {
        logger.info("getting credential definition from public ledger: $credDefId")

        val req = Ledger.buildGetCredDefRequest(did, credDefId).get()
        return extractResult(Ledger.submitRequest(pool, req).get(), Ledger::parseGetCredDefResponse)
    }

    companion object {
        private const val SIGNATURE_TYPE = "CL"
        private const val TAG = "TAG_1"

        fun verifyProof(proofReq: ProofRequest, proof: ProofInfo): Boolean {
            val proofRequestJson = SerializationUtils.anyToJSON(proofReq)
            val proofJson = SerializationUtils.anyToJSON(proof.proofData)
            val usedSchemasJson = SerializationUtils.anyToJSON(proof.usedSchemas)
            val usedClaimDefsJson = SerializationUtils.anyToJSON(proof.usedClaimDefs)

            return Anoncreds.verifierVerifyProof(
                    proofRequestJson, proofJson, usedSchemasJson, usedClaimDefsJson, "{}", "{}").get()
        }

        fun getSchemaId(did: String, name: String, version: String): String = "$did:1:$name:$version"
        fun getCredDefId(did: String, schemaSeqNo: Int): String = "$did:3:$SIGNATURE_TYPE:${schemaSeqNo}:$TAG"
    }
}