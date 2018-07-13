package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.model.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
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

        val pairwiseJson = Pairwise.getPairwise(wallet, identityRecord.did).get()
        return Pairwise(pairwiseJson).did
    }

    fun createSchema(name: String, version: String, attributes: List<String>): String {
        val attrStr = attributes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

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
            val schema = getSchema(data.schema.id)
            """"attr${idx}_referent":
                    {
                        "name":"${data.field}",
                        "schemaId":"${schema.id}",
                        "credDefId":"${data.credDefId}"
                    }
            """.trimIndent()
        }

        // 2. Add predicates
        val requestedPredicates = predicates.withIndex().joinToString {(idx, data) ->
            val schema = getSchema(data.schema.id)
            """"predicate${idx}_referent":
                    {
                        "name":"${data.field}",
                        "p_type":">=",
                        "p_value":${data.value},
                        "schemaId":"${schema.id}",
                        "credDefId":"${data.credDefId}"
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
                    ${proofAttrs.joinToString { (uuid, key, _, _) -> """ "$key": {"cred_id": "$uuid", "revealed": true} """ }}
                },
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
                ${schemaForClaim.entries.joinToString { (uuid, schema) ->  """ "${schema.id}": ${schema.json} """  }}
            }
        """.trimIndent()

        val usedClaimDef = """
            {
                ${claimDefForClaim.entries.joinToString { (uuid, definition) -> """ "$uuid": ${definition.json} """ }}
            }
        """.trimIndent()

        // 4. issue proof
        val proverProof = Anoncreds.proverCreateProof(wallet, proofReq.json.toString(), createdClaim, masterSecretId, usedSchemas, usedClaimDef, "{}").get()
        return Proof(proverProof, usedSchemas, usedClaimDef)
    }

    data class ClaimPredicateDetails(val claimUuid: String, val key: String, val schema: Schema, val definition: CredentialDefinition)

    private fun generateProofAttrs(proofReq: ProofReq, requiredClaimsForProof: JSONObject): List<ClaimPredicateDetails> {
        val attributeKeys = proofReq.attributes.keySet() as Set<String>

        val claimPredicateDetails = attributeKeys.map { attribute ->
            val schemaId = proofReq.getAttributeValue(attribute, "schemaId")
            val credDefId = proofReq.getAttributeValue(attribute, "credDefId")
            val claims = requiredClaimsForProof
                    .getJSONObject("attrs")
                    .getJSONArray(attribute)
                    .toObjectList()
                    .map { attr -> ClaimRef(attr.getJSONObject("cred_info")) }

            val claim = claims.first { it.schemaId == schemaId }
            val claimUuid = claim.referentClaim

            ClaimPredicateDetails(claimUuid, attribute, getSchema(schemaId), getClaimDef(credDefId))
        }

        return claimPredicateDetails
    }

    private fun generateProofPreds(proofReq: ProofReq, requiredClaimsForProof: JSONObject): List<ClaimPredicateDetails> {
        val predicateKeys = proofReq.predicates.keySet() as Set<String>

        val claimPredicateDetails = predicateKeys.map { predicate ->
            val schemaId = proofReq.getPredicateValue(predicate, "schemaId")
            val credDefId = proofReq.getPredicateValue(predicate, "credDefId")
            val claims = requiredClaimsForProof
                    .getJSONObject("predicates")
                    .getJSONArray(predicate)
                    .toObjectList()
                    .map { pred -> ClaimRef(pred.getJSONObject("cred_info")) }

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

    companion object {
        private const val SIGNATURE_TYPE = "CL"
        private const val TAG = "TAG_1"

        fun verifyProof(proofReq: ProofReq, proof: Proof): Boolean {
            return Anoncreds.verifierVerifyProof(
                    proofReq.json.toString(), proof.json.toString(), proof.usedSchemas, proof.usedClaimDefs, "{}", "{}").get()
        }
    }
}