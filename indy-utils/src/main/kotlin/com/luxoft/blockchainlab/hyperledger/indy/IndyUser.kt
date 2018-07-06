package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.model.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.IndyException
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

    data class ProofAttribute(val schema: SchemaDetails, val field: String, val value: String) {
        constructor(schema: SchemaDetails, field: String): this(schema, field, "")
    }
    
    data class ProofPredicate(val schema: SchemaDetails, val field: String, val value: Int)

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

    fun createRevokReg(
            schemaDetails: SchemaDetails,
            tailsWriter: BlobStorageWriter = BlobStorageWriter.openWriter("default", "{}").get()
    ) {
        val schema = getSchema(schemaDetails)
        val credDef = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, schema.json.toString(), TAG, null, "{}").get()
        Anoncreds.issuerCreateAndStoreRevocReg(wallet, did, null, TAG, credDef.credDefId, "{}", tailsWriter).get()
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

    fun createSchema(schemaDetails: SchemaDetails, schemaAttributes: List<String>) {
        if(hasSchema(schemaDetails))
            return

        val schemaRequest = Ledger.buildSchemaRequest(did, schemaDetails.json(schemaAttributes)).get()
        Ledger.signAndSubmitRequest(pool, wallet, did, schemaRequest).get()
    }

    fun createClaimDef(schemaDetails: SchemaDetails) {
        if(hasSchema(schemaDetails))
            return // todo: optimise calls to ledger 1/2

        // 1. Get ClaimSchema from public ledger
        val schema = getSchema(schemaDetails)  // todo: optimise calls to ledger 2/2

        // 2. Create ClaimDef
        val claimTemplate = Anoncreds.issuerCreateAndStoreCredentialDef(wallet, did, schema.json.toString(), TAG, SIGNATURE_TYPE, null).get()

        // 3. Publish ClaimDef to public ledfger
        val claimDefReq = Ledger.buildCredDefRequest(did, claimTemplate.credDefJson).get()
        Ledger.signAndSubmitRequest(pool, wallet, did, claimDefReq).get()
    }

    fun createClaimOffer(proverDid: String, schemaDetails: SchemaDetails): ClaimOffer {
        val (schema, credDef) = getSchemaAndDefinition(schemaDetails, did)
        val credOffer = Anoncreds.issuerCreateCredentialOffer(wallet, credDef.id).get()
        return ClaimOffer(credOffer, proverDid)
    }

    fun receiveClaimOffer(claimOffer: ClaimOffer)  {
        //todo: indy_prover_store_claim_offer was DELETED

    }

    fun createClaimReq(schemaDetails: SchemaDetails, issuerDid: String, sessionDid: String, masterSecretId: String = defaultMasterSecretId): ClaimReq {
        val claimDef = getClaimDef(schemaDetails, issuerDid)
        val claimOffer = getClaimOffer(issuerDid)

        createMasterSecret(masterSecretId)
        val credReq = Anoncreds.proverCreateCredentialReq(wallet, sessionDid, claimOffer.json, claimDef.json.toString(), masterSecretId).get()
        return ClaimReq(credReq.credentialRequestJson)
    }

    fun issueClaim(claimReq: ClaimReq,
                   proposal: String,
                   schemaDetails: SchemaDetails,
                   revokIdx: String? = null,
                   storageReader: BlobStorageReader = BlobStorageReader.openReader("default", "{}").get()
    ): Claim {
        val schema = getSchema(schemaDetails)
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
            val schemaSqNum = getSchema(data.schema).seqNo
            """"attr${idx}_referent":{"name":"${data.field}", "seqNo":$schemaSqNum}"""
        }

        // 2. Add predicates
        val requestedPredicates = StringBuilder()
        predicates.takeIf { data -> data.isNotEmpty() }?.forEachIndexed {idx, data ->
            val schemaId = getSchema(data.schema).seqNo
            requestedPredicates.append(String.format("\"predicate%d_referent\": {" +
                    "\"attr_name\":\"%s\"," +
                    "\"p_type\":\">=\"," +
                    "\"value\":%d," +
                    "\"seqNo\":%s }", idx, data.field, data.value, schemaId))

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
        val schemaForClaim: MutableMap<String, Schema> = LinkedHashMap()
        val claimDefForClaim: MutableMap<String, CredentialDefinition> = LinkedHashMap()

        logger.debug("proofReq = " + proofReq)

        val requiredClaimsForProof = JSONObject(Anoncreds.proverGetCredentialsForProofReq(wallet, proofReq.json).get())

        val extractClaimFor: (String, String, String?, (String, String) -> Unit) -> Unit = { fieldType, dataFilter, schemaId, generator ->
            val claims = requiredClaimsForProof.getJSONObject(fieldType)?.getJSONArray(dataFilter)?.
                    takeIf { it -> it.length() != 0  }

            claims?.also {indyClaims ->
                for(i in 0..indyClaims.length() - 1) {
                    val indyClaimRef = ClaimRef(indyClaims.get(i).toString())
                    val schemaDetails = SchemaDetails.fromSchemaKey(indyClaimRef.schemaKey)
                    val (schema, definition) = getSchemaAndDefinition(schemaDetails, indyClaimRef.issuerDid)
                            .takeIf { it -> schemaId == null || it.first.id == schemaId } ?: continue

                    val claimUuid = indyClaimRef.referentClaim

                    generator(dataFilter, claimUuid)

                    if (!schemaForClaim.containsKey(claimUuid))
                        schemaForClaim[claimUuid] = schema

                    if (!claimDefForClaim.containsKey(claimUuid))
                        claimDefForClaim[claimUuid] = definition

                    break
                }
            }
        }

        logger.debug("requiredClaimsForProof = " + requiredClaimsForProof)

        val createdClaim = "{" +
            generateProofAttrs(proofReq, extractClaimFor, ",") +
            generateProofPreds(proofReq, extractClaimFor, ",") +
            generateProofSelfAttestedAttrs("") + "}"


        val usedSchemas  = schemaForClaim.entries.joinToString(prefix = "{", postfix = "}") { (key, schema) -> """ ${schema.id}:${schema.json} """ }
        val usedClaimDef = claimDefForClaim.entries.joinToString(prefix = "{", postfix = "}") { (key, schema) -> """ "$key":${schema.json} """ }

        // 4. issue proof
        val proverProof = Anoncreds.proverCreateProof(wallet, proofReq.json, createdClaim, masterSecretId, usedSchemas, usedClaimDef, "{}").get()
        return Proof(proverProof, usedSchemas, usedClaimDef)
    }

    private fun generateProofSelfAttestedAttrs(comma: String): String {
        return String.format("\"self_attested_attributes\":{%s}%s", "", comma)
    }

    private fun generateProofAttrs(proofReq: ProofReq,
                                   extractClaimFor: (String, String, String?, (String, String) -> Unit) -> Unit,
                                   comma: String): String {

        // 1. Gen proof for Attributes
        val claimsGroupByAttrs = StringBuilder()
        val attributes = JSONObject(proofReq.attributes).keys()

        while (attributes.hasNext()) {
            val attribute = attributes.next() as String
            val schemaId = proofReq.getAttributeValue(attribute, "seqNo")
            extractClaimFor("attrs", attribute, schemaId, { attr, uuid ->
                claimsGroupByAttrs.append(String.format("\"%s\":[\"%s\", true]", attr, uuid))
                if (attributes.hasNext()) claimsGroupByAttrs.append(",")
            })
        }

        return String.format("\"requested_attrs\":{%s}%s", claimsGroupByAttrs.toString(), comma)
    }

    private fun generateProofPreds(proofReq: ProofReq,
                                   extractClaimFor: (String, String, String?, (String, String) -> Unit) -> Unit,
                                   comma: String): String {
        // 1. Gen proof for Attributes
        val claimsGroupByPreds = StringBuilder()
        val predicates = JSONObject(proofReq.predicates).keys()

        while (predicates.hasNext()) {
            val predicate = predicates.next() as String
            val schemaId = proofReq.getPredicateValue(predicate, "seqNo")
            extractClaimFor("predicates", predicate, schemaId, { pred, uuid ->
                claimsGroupByPreds.append(String.format("\"%s\":\"%s\"", pred, uuid))
                if (predicates.hasNext()) claimsGroupByPreds.append(",")
            })
        }

        return String.format("\"requested_predicates\":{%s}%s", claimsGroupByPreds.toString(), comma)
    }

    protected fun getSchema(schemaDetails: SchemaDetails): Schema {
        val schemaReq = Ledger.buildGetSchemaRequest(did, schemaDetails.id).get()
        val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
        val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()
        return Schema(parsedRes)
    }

    protected fun hasSchema(schemaDetails: SchemaDetails): Boolean = try {
        getSchema(schemaDetails)
        true
    } catch (e: IndyException) {
        false
    }

    protected fun getClaimDef(schemaDetails: SchemaDetails, claimDefOwner: String): CredentialDefinition {
        val schema = getSchema(schemaDetails)
        val claimDefReq = Ledger.buildGetCredDefRequest(schemaDetails.owner, schema.id).get()

        val claimDefRes = Ledger.submitRequest(pool, claimDefReq).get()

        val parsedRes = Ledger.parseGetCredDefResponse(claimDefRes).get()
        return CredentialDefinition(parsedRes)
    }

    private fun getSchemaAndDefinition(schemaDetails: SchemaDetails, claimDefOwner: String): Pair<Schema, CredentialDefinition> {
        return Pair(getSchema(schemaDetails), getClaimDef(schemaDetails, claimDefOwner))
    }

    private fun getClaimOffer(issuerDid: String): ClaimOffer {
        val claimOfferFilter = """{"issuer_did":"$issuerDid"}"""

        val claimOffersJson = Anoncreds.proverGetCredentials(wallet, claimOfferFilter).get()

        val claimOffersObject = JSONArray(claimOffersJson)

        val claimOfferObject = claimOffersObject.getJSONObject(0)
        return ClaimOffer(claimOfferObject.toString(), did)
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