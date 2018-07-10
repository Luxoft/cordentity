package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.model.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateCredentialOffer
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.blob_storage.BlobStorageReader
import org.hyperledger.indy.sdk.blob_storage.BlobStorageWriter
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pairwise.Pairwise
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletItemNotFoundException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.json.JSONArray
import kotlin.collections.LinkedHashMap


open class IndyUser {

    class SchemaDetails(val name: String, val version: String, val owner: String) {
        val id: String get() = """$owner:2:$name:$version"""  // todo: replace with sdk calls

        fun json(schemaAttributes: List<String>): String =
                """{"id":"$id", "name":"$name", "version":"$version", "attrNames":${schemaAttributes.map { "\"$it\"" }}, "ver":"1.0"}"""

        constructor() : this("", "", "")

        companion object Build {
            fun fromSchemaKey(schemaKeyJson: String): SchemaDetails {
                val parsed = JSONObject(schemaKeyJson)
                return SchemaDetails(parsed.getString("name"), parsed.getString("version"), parsed.getString("owner"))
            }
        }
    }

    class IdentityDetails(val did: String, val verKey: String, val alias: String?, val role: String?) {
        constructor(identityRecord: String) : this(
                JSONObject(identityRecord).get("did").toString(),
                JSONObject(identityRecord).get("verkey").toString(),"","")

        fun getIdentityRecord(): String {
            return String.format("{\"did\":\"%s\", \"verkey\":\"%s\"}", did, verKey)
        }
    }

    data class ProofAttribute(val schema: SchemaDetails,  val credDefId: String, val field: String, val value: String)
    
    data class ProofPredicate(val schema: SchemaDetails, val credDefId: String, val field: String, val value: Int)

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

            } catch (ex: Exception) {
                if (getRootCause(ex) !is WalletItemNotFoundException) throw ex else {
                    val didResult = Did.createAndStoreMyDid(wallet, didConfig).get()
                    _did = didResult.did
                    _verkey = didResult.verkey
                }
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
        } catch (e: DuplicateMasterSecretNameException) {
            logger.debug("MasterSecret already exists, who cares, continuing")
        }
    }

    fun createSessionDid(identityRecord: IdentityDetails): String {
        if(!Pairwise.isPairwiseExists(wallet, identityRecord.did).get()) {
            addKnownIdentities(identityRecord)
            val sessionDid = Did.createAndStoreMyDid(wallet, "{}").get().did
            Pairwise.createPairwise(wallet, identityRecord.did, sessionDid, "").get()
        }

        return Pairwise(Pairwise.getPairwise(wallet, identityRecord.did).get()).did
    }

    fun createSchema(name: String, version: String, attributes: List<String>): String {
        val attrStr = attributes.joinToString(prefix = "[", postfix = "]") { "\"it\"" }

        val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attrStr).get()
        val schemaRequest = Ledger.buildSchemaRequest(did, schemaInfo.schemaJson).get()
        Ledger.signAndSubmitRequest(pool, wallet, did, schemaRequest).get()

        assert(getSchema(schemaInfo.schemaId).id == schemaInfo.schemaId)

        return schemaInfo.schemaId
    }

    fun createClaimDef(schemaId: String): String {
        val schema = getSchema(schemaId)

        val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, schema.json.toString(), TAG, SIGNATURE_TYPE, null).get()
        val claimDefReq = Ledger.buildCredDefRequest(did, credDefInfo.credDefJson).get()
        Ledger.signAndSubmitRequest(pool, wallet, did, claimDefReq).get()

        assert(getClaimDef(credDefInfo.credDefId).id == credDefInfo.credDefId)

        return credDefInfo.credDefId
    }

    fun createClaimOffer(credDefId: String): ClaimOffer {
        val credOffer = Anoncreds.issuerCreateCredentialOffer(wallet, credDefId).get()
        return ClaimOffer(credOffer)
    }

    fun createClaimReq(issuerDid: String, sessionDid: String, credDefId: String, masterSecretId: String = defaultMasterSecretId): ClaimReq {
        val credDef = getClaimDef(credDefId)
        val credOffer = getClaimOffer(issuerDid)

        createMasterSecret(masterSecretId)

        val credReq = Anoncreds.proverCreateCredentialReq(wallet, sessionDid, credOffer.json.toString(), credDef.json.toString(), masterSecretId).get()
        return ClaimReq(credReq.credentialRequestJson)
    }

    fun issueClaim(claimReq: ClaimReq,
                   proposal: String,
                   schemaDetails: SchemaDetails,
                   revokIdx: String? = null,
                   storageReader: BlobStorageReader = BlobStorageReader.openReader("default", "{}").get()
    ): Claim {
        val schema = getSchema(schemaDetails.id)
        val credDef = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, schema.json.toString(), TAG, null, "{}").get()
        val credOffer = issuerCreateCredentialOffer(wallet, credDef.credDefId).get()
        val createClaimResult = Anoncreds.issuerCreateCredential(
                wallet, credOffer, claimReq.json, proposal, revokIdx, storageReader.blobStorageReaderHandle).get()

        return Claim(createClaimResult.credentialJson)  // TODO: json keys do not match
    }

    fun receiveClaim(claim: Claim)  {
        val credDef = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, claim.schemaKey, TAG, null, "{}").get()

        val credOffer = Anoncreds.issuerCreateCredentialOffer(wallet, credDef.credDefId).get()
        val credReqMeta = Anoncreds.proverCreateCredentialReq(wallet, claim.issuerDid, credOffer, credDef.credDefJson, defaultMasterSecretId).get()

        Anoncreds.proverStoreCredential(wallet, null, credReqMeta.credentialRequestMetadataJson, claim.json, credDef.credDefJson, "{}").get()
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
    fun createProofReq(attributes: List<ProofAttribute>, predicates: List<ProofPredicate>): ProofReq {

        // 1. Add attributes
        val requestedAttributes = attributes.withIndex().joinToString { (idx, data) ->
            // todo: replace seqNo with schemaId
            val schemaSqNum = getSchema(data.schema.id).seqNo
            """"attr${idx}_referent":{"name":"${data.field}", "schemaSeqNum":$schemaSqNum, "credDefId":"${data.credDefId}"}"""
        }

        // 2. Add predicates
        val requestedPredicates = StringBuilder()
        predicates.forEachIndexed {idx, data ->
            val schemaId = getSchema(data.schema.id).seqNo
            requestedPredicates.append(String.format("\"predicate%d_referent\": {" +
                    "\"attr_name\":\"%s\"," +
                    "\"p_type\":\">=\"," +
                    "\"value\":%d," +
                    "\"schemaSeqNum\":%s," +
                    "\"credDefId\":%s }", idx, data.field, data.value, schemaId, data.credDefId))

            if (idx != (predicates.size-1)) requestedPredicates.append(",")
        }

        return ProofReq(String.format("{" +
                "    \"nonce\":\"123432421212\",\n" +
                "    \"name\":\"proof_req_1\",\n" +
                "    \"version\":\"0.1\",\n" +
                "    \"requested_attrs\": {%s},\n" +
                "    \"requested_predicates\": {%s}\n" +
                "}", requestedAttributes, requestedPredicates.toString()))
    }

    fun createProof(proofReq: ProofReq, masterSecretId: String = defaultMasterSecretId): Proof {

        logger.debug("proofReq = " + proofReq)

        val prooverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofReq.json).get()
        val requiredClaimsForProof = JSONObject(prooverGetCredsForProofReq)

        logger.debug("requiredClaimsForProof = " + requiredClaimsForProof)


        val proofAttrs = generateProofAttrs(proofReq, requiredClaimsForProof)
        val proofPreds = generateProofPreds(proofReq, requiredClaimsForProof)

        val createdClaim = """
            {
                "self_attested_attributes":{

                },
                "requested_attributes": {
                    ${proofAttrs.joinToString { (uuid, key, _, _) -> """ "$key": {"cred_id": "$uuid", "revealed": true} """ }}
                }
                "requested_predicates": {
                    ${proofPreds.joinToString { (uuid, key, _, _) -> """ "$key": {"cred_id": "$uuid"} """ }}
                }
            }
        """.trimIndent()

        val allProofs = proofAttrs.asSequence() + proofPreds
        val schemaForClaim = allProofs.associateBy({it.claimUuid}, {it.schema})
        val claimDefForClaim =  allProofs.associateBy({it.claimUuid}, {it.definition})

        val usedSchemas  = """
            {
                ${schemaForClaim.entries.joinToString { (uuid, schema) ->  """ ${schema.id}: ${schema.json} """  }}
            }
        """.trimIndent()

        val usedClaimDef = """
            {
                ${claimDefForClaim.entries.joinToString { (uuid, definition) -> """ "$uuid": ${definition.json} """ }}
            }
        """.trimIndent()

        // 4. issue proof
        val proverProof = Anoncreds.proverCreateProof(wallet, proofReq.json, createdClaim, masterSecretId, usedSchemas, usedClaimDef, "{}").get()
        return Proof(proverProof, usedSchemas, usedClaimDef)
    }

    data class ClaimPredicateDetails(val claimUuid: String, val key: String, val schema: Schema, val definition: CredentialDefinition)

    private fun generateProofAttrs(proofReq: ProofReq, requiredClaimsForProof: JSONObject): List<ClaimPredicateDetails> {
        val attributeKeys = JSONObject(proofReq.attributes).keySet() as Set<String>

        val claimPredicateDetails = attributeKeys.map { attribute ->
            val schemaId = proofReq.getPredicateValue(attribute, "schemaSeqNum")
            val credDefId = proofReq.getAttributeValue(attribute, "credDefId")
            val claims = requiredClaimsForProof
                    .getJSONObject("requested_attrs")
                    .getJSONArray(attribute)
                    .toObjectList()
                    .map { attr -> ClaimRef(attr.getJSONObject("cred_info").toString()) }

            val claim = claims.first { it.schemaId == schemaId }
            val claimUuid = claim.referentClaim

            ClaimPredicateDetails(claimUuid, attribute, getSchema(schemaId), getClaimDef(credDefId))
        }

        return claimPredicateDetails
    }

    private fun generateProofPreds(proofReq: ProofReq, requiredClaimsForProof: JSONObject): List<ClaimPredicateDetails> {
        val predicateKeys = JSONObject(proofReq.predicates).keySet() as Set<String>

        val claimPredicateDetails = predicateKeys.map { predicate ->
            val schemaId = proofReq.getPredicateValue(predicate, "schemaSeqNum")
            val credDefId = proofReq.getAttributeValue(predicate, "credDefId")
            val claims = requiredClaimsForProof
                    .getJSONObject("requested_predicates")
                    .getJSONArray(predicate)
                    .toObjectList()
                    .map { pred -> ClaimRef(pred.getJSONObject("cred_info").toString()) }

            val claim = claims.first { it.schemaId == schemaId }
            val claimUuid = claim.referentClaim

            ClaimPredicateDetails(claimUuid, predicate, getSchema(schemaId), getClaimDef(credDefId))
        }

        return claimPredicateDetails
    }

    fun getSchema(schemaId: String): Schema {
        val schemaReq = Ledger.buildGetSchemaRequest(did, schemaId).get()
        val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
        val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()
        return Schema(parsedRes)
    }

    fun getClaimDef(credDefId: String): CredentialDefinition {
        val getCredDefRequest = Ledger.buildGetCredDefRequest(did, credDefId).get()
        val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
        val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()

        return CredentialDefinition(credDefIdInfo)
    }

    private fun getClaimOffer(issuerDid: String): ClaimOffer {
        val claimOfferFilter = """{"issuer_did":"$issuerDid"}"""

        val claimOffersJson = Anoncreds.proverGetCredentials(wallet, claimOfferFilter).get()

        val claimOfferObject = JSONArray(claimOffersJson).getJSONObject(0)

        return ClaimOffer(claimOfferObject.toString())
    }

    companion object {
        private const val SIGNATURE_TYPE = "CL"
        private const val TAG = "TAG_1"

        fun verifyProof(proofReq: ProofReq, proof: Proof): Boolean {
            return Anoncreds.verifierVerifyProof(
                    proofReq.json, proof.json, proof.usedSchemas, proof.usedClaimDefs, "{}", "{}").get()
        }
    }
}