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

        fun getTailsConfig() = """{"base_dir":"${getIndyHomePath("tails")}","uri_pattern":""}"""
                .replace('\\', '/')

        fun getCredDefConfig() = """{"support_revocation":true}"""
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

    fun createClaimDef(schemaId: String, enableRevocation: Boolean): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId)
        val schemaJson = SerializationUtils.anyToJSON(schema)

        val credDefConfigJson = if (enableRevocation) getCredDefConfig() else "{}"

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
            ledgerService.storeRevocationRegistryEntry(createClaimResult.revocRegDeltaJson, revRegId, revocationRegistryDefinition.revDefType)
        }

        return ClaimInfo(claim, createClaimResult.revocId, createClaimResult.revocRegDeltaJson)
    }

    fun receiveClaim(claimInfo: ClaimInfo, claimReq: ClaimRequestInfo, offer: ClaimOffer) {
        val revRegDefJson = if (claimInfo.claim.revRegId != null) {
            val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(claimInfo.claim.revRegId)
            SerializationUtils.anyToJSON(revRegDef)
        } else null

        val credDef = ledgerService.retrieveCredentialDefinition(offer.credDefId)

        val claimJson = SerializationUtils.anyToJSON(claimInfo.claim)
        val claimRequestMetadataJson = SerializationUtils.anyToJSON(claimReq.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        Anoncreds.proverStoreCredential(
                wallet, null, claimRequestMetadataJson, claimJson, credDefJson, revRegDefJson
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

    fun createProof(proofRequest: ProofRequest, masterSecretId: String = defaultMasterSecretId): ProofInfo {
        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val proverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofRequestJson).get()
        val requiredClaimsForProof = SerializationUtils.jSONToAny<ProofRequestCredentials>(proverGetCredsForProofReq)
                ?: throw RuntimeException("Unable to parse credentials for proof request from json")

        val requiredAttributes = requiredClaimsForProof.attrs.values.flatten()
        val proofRequestAttributes = proofRequest.requestedAttributes
        val attrProofData = parseProofData(proofRequestAttributes, requiredAttributes)

        val requiredPredicates = requiredClaimsForProof.predicates.values.flatten()
        val proofRequestPredicates = proofRequest.requestedPredicates
        val predProofData = parseProofData(proofRequestPredicates, requiredPredicates)

        val timestamp = proofRequest.nonRevoked?.to

        val requestedAttributes = attrProofData.claims
                .associate { it.key to RequestedAttributeInfo(it.claimUuid, true, timestamp) }
        val requestedPredicates = predProofData.claims
                .associate { it.key to RequestedPredicateInfo(it.claimUuid, timestamp) }
        val requestedCredentials = RequestedCredentials(requestedAttributes, requestedPredicates)

        val allSchemas = (attrProofData.schemaIds + predProofData.schemaIds)
                .map { ledgerService.retrieveSchema(it) }
        val allClaimDefs = (attrProofData.credDefIds + predProofData.credDefIds)
                .map { ledgerService.retrieveCredentialDefinition(it) }

        val usedRevocationStatesJson = if (proofRequest.nonRevoked != null) {
            val allRevocationStates = (attrProofData.revIds + predProofData.revIds)
                    .map {
                        it.credRevId ?: throw RuntimeException("No credRevId found on $it")
                        it.revRegId ?: throw RuntimeException("No revRegId found on $it")

                        Pair(it.revRegId, getRevocationState(it.credRevId, it.revRegId, proofRequest.nonRevoked))
                    }

            val usedRevocationStates = allRevocationStates
                    .associate { (revRegId, revocationState) ->
                        val stateByTimestamp = mutableMapOf<Int, RevocationState>()
                        stateByTimestamp[revocationState.timestamp] = revocationState

                        revRegId to stateByTimestamp
                    }

            SerializationUtils.anyToJSON(usedRevocationStates)
        } else "{}"

        val usedSchemas = allSchemas.associate { it.id to it }
        val usedClaimDefs = allClaimDefs.associate { it.id to it }

        val requestedCredentialsJson = SerializationUtils.anyToJSON(requestedCredentials)
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)
        val usedClaimDefsJson = SerializationUtils.anyToJSON(usedClaimDefs)

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

    private fun parseProofData(
            collectionFromRequest: Map<String, AbstractClaimReference>,
            collectionFromCreds: List<ClaimReferenceInfo>
    ): ProofData {

        val schemaIds = collectionFromCreds
                .map { it.credInfo.schemaId }
                .distinct()

        val credDefIds = collectionFromCreds
                .map { it.credInfo.credDefId }
                .distinct()

        val revIds = collectionFromCreds
                .map { RevocationIds(it.credInfo.credRevId, it.credInfo.revRegId) }
                .distinct()

        val referentClaims = collectionFromCreds
                .map { attribute ->
                    val keys = collectionFromRequest.entries
                            .filter { it.value.schemaId == attribute.credInfo.schemaId }
                            .map { it.key }

                    val reference = attribute.credInfo.referent

                    keys.map { key -> ReferentClaim(key, reference) }
                }
                .flatten()
                .distinct()

        return ProofData(referentClaims, schemaIds, credDefIds, revIds)
    }

    private fun getRevocationState(credRevId: String, revRegDefId: String, interval: Interval): RevocationState {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegDefId)
        val revRegDefJson = SerializationUtils.anyToJSON(revRegDef)

        val revRegDelta = ledgerService.retrieveRevocationRegistryDelta(revRegDefId, interval)
        val revRegDeltaJson = SerializationUtils.anyToJSON(revRegDelta)

        val revStateJson = Anoncreds.createRevocationState(
                tailsReaderHandle, revRegDefJson, revRegDeltaJson, interval.to, credRevId
        ).get()

        return SerializationUtils.jSONToAny<RevocationState>(revStateJson)
                ?: throw RuntimeException("Unable to parse revocation state from json")
    }

    fun verifyProof(proofReq: ProofRequest, proof: ProofInfo): Boolean {
        val proofRequestJson = SerializationUtils.anyToJSON(proofReq)
        val proofJson = SerializationUtils.anyToJSON(proof.proofData)
        val usedSchemasJson = SerializationUtils.anyToJSON(proof.usedSchemas)
        val usedClaimDefsJson = SerializationUtils.anyToJSON(proof.usedClaimDefs)

        val (revRegDefsJson, revRegDeltasJson) = if (proofReq.nonRevoked != null) {
            val revRegDefs = proof.proofData.identifiers
                    .map { ledgerService.retrieveRevocationRegistryDefinition(it.revRegId!!) }
                    .associate { it.id to it }

            val revRegDeltas = proof.proofData.identifiers
                    .map {
                        val delta = ledgerService.retrieveRevocationRegistryEntry(it.revRegId!!, proofReq.nonRevoked.to)
                        val map = hashMapOf<Int, RevocationRegistryEntry>()
                        map[proofReq.nonRevoked.to] = delta

                        Pair(it.revRegId, map)
                    }
                    .associate { it.first to it.second }

            Pair(SerializationUtils.anyToJSON(revRegDefs), SerializationUtils.anyToJSON(revRegDeltas))
        } else Pair("{}", "{}")

        val result = Anoncreds.verifierVerifyProof(
                proofRequestJson, proofJson, usedSchemasJson, usedClaimDefsJson, revRegDefsJson, revRegDeltasJson
        ).get()

        return result
    }

    class ArtifactDoesntExist(id: String) : IllegalArgumentException("Artifact with id $id doesnt exist on public ledger")
}