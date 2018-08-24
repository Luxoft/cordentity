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
        fun getIdentityRecord() = """{"did":"$did","verkey":"$verkey"}"""
    }

    companion object {
        private const val SIGNATURE_TYPE = "CL"
        private const val TAG = "TAG_1"
        private const val REV_TAG = "REV_TAG_1"
        private const val ISSUANCE_ON_DEMAND = "ISSUANCE_ON_DEMAND"
        private const val EMPTY_OBJECT = "{}"

        fun getTailsConfig() = """{"base_dir":"${getIndyHomePath("tails")}","uri_pattern":""}"""
                .replace('\\', '/')

        fun getCredDefConfig() = """{"support_revocation":true}"""

        fun verifyProof(did: String, pool: Pool, proofReq: ProofRequest, proof: ProofInfo): Boolean {
            val proofRequestJson = SerializationUtils.anyToJSON(proofReq)
            val proofJson = SerializationUtils.anyToJSON(proof.proofData)

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

            val (revRegDefsJson, revRegDeltasJson) = if (proofReq.nonRevoked != null) {
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

            return Anoncreds.verifierVerifyProof(
                    proofRequestJson, proofJson, usedSchemasJson, usedClaimDefsJson, revRegDefsJson, revRegDeltasJson
            ).get()
        }

        fun createProofRequest(
                version: String = "0.1",
                name: String = "proof_req_$version",
                nonce: String = "123432421212",
                attributes: List<CredFieldRef>,
                predicates: List<CredPredicate>,
                nonRevoked: Interval?
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

    constructor(wallet: Wallet, did: String? = null, didConfig: String = EMPTY_OBJECT) :
            this(PoolManager.getInstance().pool, wallet, did, didConfig)

    constructor(pool: Pool, wallet: Wallet, did: String?, didConfig: String = EMPTY_OBJECT) {

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
            val sessionDid = Did.createAndStoreMyDid(wallet, EMPTY_OBJECT).get().did
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

    fun createClaimDef(schemaId: String, enableRevocation: Boolean): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId)
                ?: throw RuntimeException("Schema $schemaId doesn't exist in ledger")
        val schemaJson = SerializationUtils.anyToJSON(schema)

        val credDefConfigJson = if (enableRevocation) getCredDefConfig() else EMPTY_OBJECT

        val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(
                wallet, did, schemaJson, TAG, SIGNATURE_TYPE, credDefConfigJson
        ).get()

        val credDef = SerializationUtils.jSONToAny<CredentialDefinition>(credDefInfo.credDefJson)
                ?: throw RuntimeException("Unable to parse credential definition from json")

        ledgerService.storeCredentialDefinition(credDef)

        return credDef
    }

    fun createRevocationRegistry(credentialDefinition: CredentialDefinition, maxCredentialNumber: Int = 5): RevocationRegistryInfo {
        val revRegDefConfig = RevocationRegistryConfig(ISSUANCE_ON_DEMAND, maxCredentialNumber)
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

    fun createClaimOffer(credDefId: String): ClaimOffer {
        val credOfferJson = Anoncreds.issuerCreateCredentialOffer(wallet, credDefId).get()

        return SerializationUtils.jSONToAny<ClaimOffer>(credOfferJson)
                ?: throw RuntimeException("Unable to parse claim offer from json")
    }

    fun createClaimReq(sessionDid: String, offer: ClaimOffer, masterSecretId: String = defaultMasterSecretId): ClaimRequestInfo {
        val credDef = ledgerService.retrieveCredentialDefinition(offer.credDefId)
                ?: throw RuntimeException("Credential definition ${offer.credDefId} doesn't exist in ledger")

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

    fun issueClaim(claimReq: ClaimRequestInfo, proposal: String, offer: ClaimOffer, revRegId: String? = null): ClaimInfo {
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

        if (revRegId != null) {
            val revocationRegistryDefinition = ledgerService.retrieveRevocationRegistryDefinition(revRegId)
                    ?: throw RuntimeException("Revocation registry definition $revRegId doesn't exist in ledger")

            val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createClaimResult.revocRegDeltaJson)
                    ?: throw RuntimeException("Unable to parse revocation registry delta from json")

            ledgerService.storeRevocationRegistryEntry(revRegDelta, revRegId, revocationRegistryDefinition.revDefType)
        }

        return ClaimInfo(claim, createClaimResult.revocId, createClaimResult.revocRegDeltaJson)
    }

    fun revokeClaim(revRegId: String, credRevId: String) {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle
        val revRegDeltaJson = Anoncreds.issuerRevokeCredential(wallet, tailsReaderHandle, revRegId, credRevId).get()
        val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson)
                ?: throw RuntimeException("Unable to parse revocation registry delta from json")
        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegId)
                ?: throw RuntimeException("Revocation registry definition $revRegId doesn't exist in ledger")

        ledgerService.storeRevocationRegistryEntry(revRegDelta, revRegId, revRegDef.revDefType)
    }

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

    fun createProof(proofRequest: ProofRequest, masterSecretId: String = defaultMasterSecretId): ProofInfo {
        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val proverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofRequestJson).get()
        val requiredClaimsForProof = SerializationUtils.jSONToAny<ProofRequestCredentials>(proverGetCredsForProofReq)
                ?: throw RuntimeException("Unable to parse credentials for proof request from json")

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
                ?: throw RuntimeException("Unable to parse proof from json")

        return ProofInfo(proof)
    }

    data class ProofDataEntry(
            val schemaId: String,
            val credDefId: String,
            val referentClaims: List<ReferentClaim>,
            val revState: RevocationState?
    )

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
                ?: throw RuntimeException("Unable to parse revocation state from json")

        revState.revRegId = revRegDefId

        return revState
    }
}