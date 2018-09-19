package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.StorageUtils
import junit.framework.Assert.assertFalse
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.did.DidResults
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue


class AnoncredsDemoTest : IndyIntegrationTest() {
    private val masterSecretId = "masterSecretId"
    private val gvtCredentialValues = GVT_CRED_VALUES
    private val xyzCredentialValues = """{"status":{"raw":"partial","encoded":"51792877103171595686471452153480627530895"},"period":{"raw":"8","encoded":"8"}}"""

    private val issuerWalletConfig = SerializationUtils.anyToJSON(WalletConfig("issuerWallet"))
    private val issuer2WalletConfig = SerializationUtils.anyToJSON(WalletConfig("issuer2Wallet"))
    private val proverWalletConfig = SerializationUtils.anyToJSON(WalletConfig("proverWallet"))

    private lateinit var issuerWallet: Wallet
    private lateinit var issuer2Wallet: Wallet
    private lateinit var proverWallet: Wallet
    private lateinit var issuerDidInfo: DidResults.CreateAndStoreMyDidResult
    private lateinit var issuer2DidInfo: DidResults.CreateAndStoreMyDidResult
    private lateinit var proverDidInfo: DidResults.CreateAndStoreMyDidResult
    private lateinit var issuer1: IndyUser
    private lateinit var issuer2: IndyUser
    private lateinit var prover: IndyUser

    companion object {
        private lateinit var pool: Pool
        private lateinit var poolName: String

        @JvmStatic
        @BeforeClass
        fun setUpTest() {
            // Create and Open Pool
            poolName = PoolManager.DEFAULT_POOL_NAME
            pool = PoolManager.openIndyPool(PoolManager.defaultGenesisResource, poolName)
        }

        @JvmStatic
        @AfterClass
        fun tearDownTest() {
            // Close pool
            pool.closePoolLedger().get()
            Pool.deletePoolLedgerConfig(poolName)
        }
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        // Clean indy stuff
        StorageUtils.cleanupStorage()

        // Issuer Create and Open Wallet
        Wallet.createWallet(issuerWalletConfig, CREDENTIALS).get()
        issuerWallet = Wallet.openWallet(issuerWalletConfig, CREDENTIALS).get()

        Wallet.createWallet(issuer2WalletConfig, CREDENTIALS).get()
        issuer2Wallet = Wallet.openWallet(issuer2WalletConfig, CREDENTIALS).get()

        // Prover Create and Open Wallet
        Wallet.createWallet(proverWalletConfig, CREDENTIALS).get()
        proverWallet = Wallet.openWallet(proverWalletConfig , CREDENTIALS).get()

        val trusteeDidInfo = createTrusteeDid(issuerWallet)
        issuerDidInfo = createDid(issuerWallet)
        linkIssuerToTrustee(trusteeDidInfo.did, issuerWallet, issuerDidInfo)

        issuer2DidInfo = createDid(issuer2Wallet)
        linkIssuerToTrustee(trusteeDidInfo.did, issuerWallet, issuer2DidInfo)

        proverDidInfo = createDid(proverWallet)
        linkProverToIssuer(issuerDidInfo.did, issuerWallet, proverDidInfo)

        issuer1 = IndyUser(pool, issuerWallet, issuerDidInfo.did)
        issuer2 = IndyUser(pool, issuer2Wallet, issuer2DidInfo.did)
        prover = IndyUser(pool, proverWallet, proverDidInfo.did)
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        // Issuer Remove Wallet
        issuerWallet.closeWallet().get()
        Wallet.deleteWallet(issuerWalletConfig, CREDENTIALS).get()

        issuer2Wallet.closeWallet().get()
        Wallet.deleteWallet(issuer2WalletConfig, CREDENTIALS).get()

        // Prover Remove Wallet
        proverWallet.closeWallet().get()
        Wallet.deleteWallet(proverWalletConfig, CREDENTIALS).get()

        // Clean indy stuff
        StorageUtils.cleanupStorage()
    }

    private fun createTrusteeDid(wallet: Wallet) = Did.createAndStoreMyDid(wallet, """{"seed":"$TRUSTEE_SEED"}""").get()
    private fun createDid(wallet: Wallet) = Did.createAndStoreMyDid(wallet, "{}").get()

    private fun linkIssuerToTrustee(trusteeDid: String, issuerWallet: Wallet, issuerDidInfo: DidResults.CreateAndStoreMyDidResult) {
        val target = IndyUser.IdentityDetails(issuerDidInfo.did, issuerDidInfo.verkey, null, "TRUSTEE")
        LedgerService.addNym(trusteeDid, pool, issuerWallet, target)
    }

    private fun linkProverToIssuer(issuerDid: String, issuerWallet: Wallet, proverDidInfo: DidResults.CreateAndStoreMyDidResult) {
        val target = IndyUser.IdentityDetails(proverDidInfo.did, proverDidInfo.verkey, null, null)
        LedgerService.addNym(issuerDid, pool, issuerWallet, target)
    }

    @Test
    @Throws(Exception::class)
    fun `revocation works fine`() {
        val gvtSchema = issuer1.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createClaimDefinition(gvtSchema.id, true)
        val revRegInfo = issuer1.createRevocationRegistry(credDef.id)

        prover.createMasterSecret(masterSecretId)

        val credOffer = issuer1.createClaimOffer(credDef.id)
        val credReq = prover.createClaimRequest(prover.did, credOffer, masterSecretId)
        val claimInfo = issuer1.issueClaim(credReq, gvtCredentialValues, credOffer, revRegInfo.definition.id)
        prover.receiveClaim(claimInfo, credReq, credOffer)

        Thread.sleep(3000)

        val field_name = CredFieldRef("name", gvtSchema.id, credDef.id)
        val field_sex = CredFieldRef("sex", gvtSchema.id, credDef.id)
        val field_age = CredFieldRef("age", gvtSchema.id, credDef.id)
        val proofReq = IndyUser.createProofRequest(
                attributes = listOf(field_name, field_sex),
                predicates = listOf(CredPredicate(field_age, 18)),
                nonRevoked = Interval.recent()
        )

        val proof = prover.createProof(proofReq, masterSecretId)

        val usedData = IndyUser.getDataUsedInProof(DID_MY1, pool, proofReq, proof)
        assertEquals("Alex", proof.proofData.requestedProof.revealedAttrs["name"]!!.raw)
        assertTrue(IndyUser.verifyProof(proofReq, proof, usedData))

        issuer1.revokeClaim(claimInfo.claim.revRegId!!, claimInfo.credRevocId!!)
        Thread.sleep(3000)

        val proofReqAfterRevocation = IndyUser.createProofRequest(
                attributes = listOf(field_name, field_sex),
                predicates = listOf(CredPredicate(field_age, 18)),
                nonRevoked = Interval.recent()
        )
        val proofAfterRevocation = prover.createProof(proofReqAfterRevocation, masterSecretId)

        val usedDataAfterRevocation = IndyUser.getDataUsedInProof(DID_MY1, pool, proofReqAfterRevocation, proofAfterRevocation)

        assertFalse(IndyUser.verifyProof(proofReqAfterRevocation, proofAfterRevocation, usedDataAfterRevocation))
    }

    @Test
    @Throws(Exception::class)
    fun `1 issuer 1 prover 1 claim setup works fine`() {
        val gvtSchema = issuer1.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createClaimDefinition(gvtSchema.id, false)

        prover.createMasterSecret(masterSecretId)

        val credOffer = issuer1.createClaimOffer(credDef.id)
        val credReq = prover.createClaimRequest(prover.did, credOffer, masterSecretId)
        val claimInfo = issuer1.issueClaim(credReq, gvtCredentialValues, credOffer, null)
        prover.receiveClaim(claimInfo, credReq, credOffer)

        val field_name = CredFieldRef("name", gvtSchema.id, credDef.id)
        val field_sex = CredFieldRef("sex", gvtSchema.id, credDef.id)
        val field_age = CredFieldRef("age", gvtSchema.id, credDef.id)
        val proofReq = IndyUser.createProofRequest(
                attributes = listOf(field_name, field_sex),
                predicates = listOf(CredPredicate(field_age, 18)),
                nonRevoked = null
        )

        val proof = prover.createProof(proofReq, masterSecretId)

        val usedData = IndyUser.getDataUsedInProof(DID_MY1, pool, proofReq, proof)

        assertEquals("Alex", proof.proofData.requestedProof.revealedAttrs["name"]!!.raw)
        assertTrue(IndyUser.verifyProof(proofReq, proof, usedData))
    }

    @Test
    @Throws(Exception::class)
    fun `2 issuers 1 prover 2 claims setup works fine`() {
        val schema1 = issuer1.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef1 = issuer1.createClaimDefinition(schema1.id, false)

        val schema2 = issuer2.createSchema(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val credDef2 = issuer2.createClaimDefinition(schema2.id, false)

        prover.createMasterSecret(masterSecretId)

        val gvtCredOffer = issuer1.createClaimOffer(credDef1.id)
        val xyzCredOffer = issuer2.createClaimOffer(credDef2.id)

        val gvtCredReq = prover.createClaimRequest(prover.did, gvtCredOffer, masterSecretId)
        val gvtCredential = issuer1.issueClaim(gvtCredReq, gvtCredentialValues, gvtCredOffer, null)
        prover.receiveClaim(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createClaimRequest(prover.did, xyzCredOffer, masterSecretId)
        val xyzCredential = issuer2.issueClaim(xyzCredReq, xyzCredentialValues, xyzCredOffer, null)
        prover.receiveClaim(xyzCredential, xyzCredReq, xyzCredOffer)

        val field_name = CredFieldRef("name", schema1.id, credDef1.id)
        val field_age = CredFieldRef("age", schema1.id, credDef1.id)
        val field_status = CredFieldRef("status", schema2.id, credDef2.id)
        val field_period = CredFieldRef("period", schema2.id, credDef2.id)

        val proofReq = IndyUser.createProofRequest(
                attributes = listOf(field_name, field_status),
                predicates = listOf(CredPredicate(field_age, 18), CredPredicate(field_period, 5)),
                nonRevoked = null
        )

        val proof = prover.createProof(proofReq, masterSecretId)

        // Verifier verify Proof
        val revealedAttr0 = proof.proofData.requestedProof.revealedAttrs["name"]!!
        assertEquals("Alex", revealedAttr0.raw)

        val revealedAttr1 = proof.proofData.requestedProof.revealedAttrs["status"]!!
        assertEquals("partial", revealedAttr1.raw)

        val usedData = prover.getDataUsedInProof(proofReq, proof)

        assertTrue(IndyUser.verifyProof(proofReq, proof, usedData))
    }

    @Test
    @Throws(Exception::class)
    fun `1 issuer 1 prover 2 claims setup works fine`() {
        val gvtSchema = issuer1.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val gvtCredDef = issuer1.createClaimDefinition(gvtSchema.id, false)

        val xyzSchema = issuer1.createSchema(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val xyzCredDef = issuer1.createClaimDefinition(xyzSchema.id, false)

        prover.createMasterSecret(masterSecretId)

        val gvtCredOffer = issuer1.createClaimOffer(gvtCredDef.id)
        val xyzCredOffer = issuer1.createClaimOffer(xyzCredDef.id)

        val gvtCredReq = prover.createClaimRequest(prover.did, gvtCredOffer, masterSecretId)
        val gvtCredential = issuer1.issueClaim(gvtCredReq, gvtCredentialValues, gvtCredOffer, null)
        prover.receiveClaim(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createClaimRequest(prover.did, xyzCredOffer, masterSecretId)
        val xyzCredential = issuer1.issueClaim(xyzCredReq, xyzCredentialValues, xyzCredOffer, null)
        prover.receiveClaim(xyzCredential, xyzCredReq, xyzCredOffer)

        val field_name = CredFieldRef("name", gvtSchema.id, gvtCredDef.id)
        val field_age = CredFieldRef("age", gvtSchema.id, gvtCredDef.id)
        val field_status = CredFieldRef("status", xyzSchema.id, xyzCredDef.id)
        val field_period = CredFieldRef("period", xyzSchema.id, xyzCredDef.id)

        val proofReq = IndyUser.createProofRequest(
                attributes = listOf(field_name, field_status),
                predicates = listOf(CredPredicate(field_age, 18), CredPredicate(field_period, 5)),
                nonRevoked = null
        )

        val proof = prover.createProof(proofReq, masterSecretId)

        // Verifier verify Proof
        val revealedAttr0 = proof.proofData.requestedProof.revealedAttrs["name"]!!
        assertEquals("Alex", revealedAttr0.raw)

        val revealedAttr1 = proof.proofData.requestedProof.revealedAttrs["status"]!!
        assertEquals("partial", revealedAttr1.raw)

        val usedData = prover.getDataUsedInProof(proofReq, proof)

        assertTrue(IndyUser.verifyProof(proofReq, proof, usedData))
    }
}
