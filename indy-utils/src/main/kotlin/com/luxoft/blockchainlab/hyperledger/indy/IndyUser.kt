package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.model.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.*
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
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException


/**
 * The central class that incapsulates Indy SDK calls and keeps the corresponding state.
 *
 * Create one instance per each server node that deals with Indy Ledger.
 */
open class IndyUser {

    @CordaSerializable
    data class SchemaDetails(val name: String, val version: String, val owner: String) {
        val filter = """{name:${name},version:${version},owner:${owner}}"""
    }

    @CordaSerializable
    data class CredentialDefDetails(val schemaSeqNo: String, val owner: String) {
        val filter = """{schemaSeqNo:${schemaSeqNo},owner:${owner}}"""
    }

    class IdentityDetails(val did: String, val verKey: String, val alias: String?, val role: String?) {
        constructor(identityRecord: String) : this(
                JSONObject(identityRecord).get("did").toString(),
                JSONObject(identityRecord).get("verkey").toString(),"","")

        fun getIdentityRecord(): String {
            return String.format("{\"did\":\"%s\", \"verkey\":\"%s\"}", did, verKey)
        }
    }

    private val logger = LoggerFactory.getLogger(IndyUser::class.java.name)

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

        if(did != null) {
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
                identityDetails.verKey,
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
        if(!Pairwise.isPairwiseExists(wallet, identityRecord.did).get()) {
            addKnownIdentities(identityRecord)
            val sessionDid = Did.createAndStoreMyDid(wallet, "{}").get().did
            Pairwise.createPairwise(wallet, identityRecord.did, sessionDid, "").get()
        }

        val pairwiseJson = Pairwise.getPairwise(wallet, identityRecord.did).get()
        return Pairwise(pairwiseJson).did
    }

    fun createSchema(name: String, version: String, attributes: List<String>): Schema {
        val attrStr = attributes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attrStr).get()
        val schemaRequest = Ledger.buildSchemaRequest(did, schemaInfo.schemaJson).get()
        Ledger.signAndSubmitRequest(pool, wallet, did, schemaRequest).get()

        val schema = getSchema(schemaInfo.schemaId)
        assert(schema.id == schemaInfo.schemaId)

        return schema
    }

    fun createClaimDef(schemaId: String): CredentialDefinition {
        val schema = getSchema(schemaId)

        // Let's hope this format is correct and stays unchanged
        val supposedCredDefId = "$did:3:$SIGNATURE_TYPE:${schema.seqNo}:$TAG"

        val credDefId = try {
            val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, schema.json.toString(), TAG, SIGNATURE_TYPE, null).get()
            val claimDefReq = Ledger.buildCredDefRequest(did, credDefInfo.credDefJson).get()
            Ledger.signAndSubmitRequest(pool, wallet, did, claimDefReq).get()

            val credDefId = credDefInfo.credDefId
            assert(supposedCredDefId == credDefId) { "CredDefId format has been changed from $supposedCredDefId to $credDefId (not for production 0_o)" }

            credDefId
        } catch (e: Exception) {
            if(e.cause !is CredDefAlreadyExistsException) throw e.cause ?: e

            // TODO: this have to be removed when IndyRegistry will be implemented
            logger.error("Credential Definiton for ${schemaId} already exist.")

            supposedCredDefId
        }

        val credDef = getClaimDef(credDefId)
        assert(credDef.id == credDefId)

        return credDef
    }

    fun createClaimOffer(credDefId: String): ClaimOffer {
        val credOffer = Anoncreds.issuerCreateCredentialOffer(wallet, credDefId).get()
        return ClaimOffer(credOffer)
    }

    fun createClaimReq(issuerDid: String, sessionDid: String, offer: ClaimOffer, masterSecretId: String = defaultMasterSecretId): ClaimReq {
        val credDef = getClaimDef(offer.credDefId)

        createMasterSecret(masterSecretId)

        val credReq = Anoncreds.proverCreateCredentialReq(wallet, sessionDid, offer.json.toString(), credDef.json.toString(), masterSecretId).get()
        return ClaimReq(credReq)
    }

    fun issueClaim(claimReq: ClaimReq, proposal: String, offer: ClaimOffer): Claim {
        val createClaimResult = Anoncreds.issuerCreateCredential(wallet, offer.json.toString(), claimReq.json.toString(), proposal, null, -1).get()
        return Claim(createClaimResult.credentialJson)
    }

    fun receiveClaim(claim: Claim, claimReq: ClaimReq, offer: ClaimOffer)  {
        val credDef = getClaimDef(offer.credDefId)

        Anoncreds.proverStoreCredential(wallet, null, claimReq.metadata, claim.json.toString(), credDef.json.toString(), null).get()
    }

    @CordaSerializable
    class CredFieldRef(val fieldName: String, val schemaId: String, val credDefId: String)

    @CordaSerializable
    class CredPredicate(val fieldRef: CredFieldRef, val value: Int, val type: String = ">=")

    /**
     * @brief - generate proof request to convince that specific fields are valid
     * @param attributes - list of attributes from schema to request for proof
     * @param predicates - list of predicates/assumptions that should be proofed by other side
     *
     * @note Sample: I'm Alex and my age is more that 18.
     * Predicate here: value '18', field 'age' (great then 18)
     * Arguments: name 'Alex'
     */
    fun createProofReq(attributes: List<CredFieldRef>, predicates: List<CredPredicate>): ProofReq {

        // 1. Add attributes
        val requestedAttributes = attributes.withIndex().joinToString { (idx, data) ->
            """"attr${idx}_referent":
                    {
                        "name":"${data.fieldName}",
                        "schemaId":"${data.schemaId}",
                        "credDefId":"${data.credDefId}"
                    }
            """.trimIndent()
        }

        // 2. Add predicates
        val requestedPredicates = predicates.withIndex().joinToString {(idx, predicate) ->
            val fieldRef = predicate.fieldRef
            """"predicate${idx}_referent":
                    {
                        "name":"${fieldRef.fieldName}",
                        "p_type":"${predicate.type}",
                        "p_value":${predicate.value},
                        "schemaId":"${fieldRef.schemaId}",
                        "credDefId":"${fieldRef.credDefId}"
                    }
            """.trimIndent()
        }

        val jsonStr = """
            {
                "version":"0.1",
                "name":"proof_req_1",
                "nonce":"123432421212",
                "requested_attributes": {$requestedAttributes},
                "requested_predicates": {$requestedPredicates}
            }""".trimIndent()

        return ProofReq(jsonStr)
    }

    fun createProof(proofReq: ProofReq, masterSecretId: String = defaultMasterSecretId): Proof {

        logger.debug("proofReq = " + proofReq)

        /*
         *  {
         *         "attrs": {
         *             "<attr_referent>": [{ cred_info: <credential_info>, interval: Optional<non_revoc_interval> }],
         *             ...,
         *         },
         *         "predicates": {
         *             "requested_predicates": [{ cred_info: <credential_info>, timestamp: Optional<integer> }, { cred_info: <credential_2_info>, timestamp: Optional<integer> }],
         *             "requested_predicate_2_referent": [{ cred_info: <credential_2_info>, timestamp: Optional<integer> }]
         *         }
         *     },
         *
         *     where credential is
         *
         *     {
         *         "referent": <string>,
         *         "attrs": [{"attr_name" : "attr_raw_value"}],
         *         "schema_id": string,
         *         "cred_def_id": string,
         *         "rev_reg_id": Optional<int>,
         *         "cred_rev_id": Optional<int>,
         *     }
	     **/

        val prooverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofReq.json.toString()).get()
        val requiredClaimsForProof = JSONObject(prooverGetCredsForProofReq)

        logger.debug("requiredClaimsForProof = " + requiredClaimsForProof)


        val proofAttrs = generateProofAttrs(proofReq, requiredClaimsForProof)
        val proofPreds = generateProofPreds(proofReq, requiredClaimsForProof)

        val createdClaim = """
            {
                "self_attested_attributes": {

                },
                "requested_attributes": {
                    ${proofAttrs.claims.joinToString { (key, uuid) -> """ "$key": {"cred_id": "$uuid", "revealed": true} """ }}
                },
                "requested_predicates": {
                    ${proofPreds.claims.joinToString { (key, uuid) -> """ "$key": {"cred_id": "$uuid"} """ }}
                }
            }
        """.trimIndent()

        val allSchemas = (proofAttrs.schemaIds + proofPreds.schemaIds).map { schemaId -> getSchema(schemaId) }
        val allClaimDefs = (proofAttrs.credDefIds + proofPreds.credDefIds).map { credDefId -> getClaimDef(credDefId) }

        val usedSchemas  = """
            {
                ${allSchemas.joinToString { schema ->  """ "${schema.id}": ${schema.json} """  }}
            }
        """.trimIndent()

        val usedClaimDef = """
            {
                ${allClaimDefs.joinToString { claimDef -> """ "${claimDef.id}": ${claimDef.json} """ }}
            }
        """.trimIndent()

        // 4. issue proof
        val proverProof = Anoncreds.proverCreateProof(wallet, proofReq.json.toString(), createdClaim, masterSecretId, usedSchemas, usedClaimDef, "{}").get()
        return Proof(proverProof, usedSchemas, usedClaimDef)
    }

    data class ReferentClaim(val key: String, val claimUuid: String)

    data class ClaimPredicateDetails(val claims: List<ReferentClaim>, val schemaIds: Set<String>, val credDefIds: Set<String>)

    private fun generateProofAttrs(proofReq: ProofReq, requiredClaimsForProof: JSONObject): ClaimPredicateDetails {
        val attributeKeys = proofReq.attributes.keySet() as Set<String>

        val schemaIds = mutableSetOf<String>()
        val credDefIds = mutableSetOf<String>()

        val referentClaims = attributeKeys.map { attribute ->
            val schemaId = proofReq.getAttributeValue(attribute, "schemaId")
            val credDefId = proofReq.getAttributeValue(attribute, "credDefId")

            schemaIds.add(schemaId)
            credDefIds.add(credDefId)

            val claims = requiredClaimsForProof
                    .getJSONObject("attrs")
                    .getJSONArray(attribute)
                    .toObjectList()
                    .map { attr -> ClaimRef(attr.getJSONObject("cred_info")) }

            val claim = claims.first { it.schemaId == schemaId }
            val claimUuid = claim.referentClaim

            ReferentClaim(attribute, claimUuid)
        }

        return ClaimPredicateDetails(referentClaims, schemaIds, credDefIds)
    }

    private fun generateProofPreds(proofReq: ProofReq, requiredClaimsForProof: JSONObject): ClaimPredicateDetails {
        val predicateKeys = proofReq.predicates.keySet() as Set<String>

        val schemaIds = mutableSetOf<String>()
        val credDefIds = mutableSetOf<String>()

        val referentClaims = predicateKeys.map { predicate ->
            val schemaId = proofReq.getPredicateValue(predicate, "schemaId")
            val credDefId = proofReq.getPredicateValue(predicate, "credDefId")

            schemaIds.add(schemaId)
            credDefIds.add(credDefId)

            val claims = requiredClaimsForProof
                    .getJSONObject("predicates")
                    .getJSONArray(predicate)
                    .toObjectList()
                    .map { pred -> ClaimRef(pred.getJSONObject("cred_info")) }

            val claim = claims.first { it.schemaId == schemaId }
            val claimUuid = claim.referentClaim

            ReferentClaim(predicate, claimUuid)
        }

        return ClaimPredicateDetails(referentClaims, schemaIds, credDefIds)
    }

    fun getSchema(schemaId: String): Schema {
        val schemaReq = Ledger.buildGetSchemaRequest(did, schemaId).get()
        val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
        val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()

        val schema = Schema(parsedRes.objectJson)
        if(!schema.isValid())
            throw ArtifactDoesntExist(schemaId)

        return schema
    }

    fun getClaimDef(credDefId: String): CredentialDefinition {
        val getCredDefRequest = Ledger.buildGetCredDefRequest(did, credDefId).get()
        val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
        val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()

        return CredentialDefinition(credDefIdInfo.objectJson)
    }

    companion object {
        private const val SIGNATURE_TYPE = "CL"
        private const val TAG = "TAG_1"

        fun verifyProof(proofReq: ProofReq, proof: Proof): Boolean {
            return Anoncreds.verifierVerifyProof(
                    proofReq.json.toString(), proof.json.toString(), proof.usedSchemas, proof.usedClaimDefs, "{}", "{}").get()
        }
    }

    class ArtifactDoesntExist(id: String) : IllegalArgumentException("Artifact with id ${id} doesnt exist on public ledger")
}