package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.InitHelper
import com.luxoft.blockchainlab.hyperledger.indy.utils.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.StorageUtils
import junit.framework.Assert.assertFalse
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.did.DidResults
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test


class AnoncredsDemoTest : IndyIntegrationTest() {

    private lateinit var pool: Pool
    private lateinit var issuerWallet: Wallet
    private lateinit var issuer2Wallet: Wallet
    private lateinit var proverWallet: Wallet
    private lateinit var poolName: String
    private val masterSecretId = "masterSecretId"
    private val gvtCredentialValues = GVT_CRED_VALUES
    private val xyzCredentialValues = """{"status":{"raw":"partial","encoded":"51792877103171595686471452153480627530895"},"period":{"raw":"8","encoded":"8"}}"""

    private val proverWalletName = "proverWallet"
    private val issuerWalletName = "issuerWallet"
    private val issuer2WalletName = "issuer2Wallet"

    @Before
    @Throws(Exception::class)
    fun setUp() {
        // Init libindy
        InitHelper.init()

        // Clean indy stuff
        StorageUtils.cleanupStorage()

        // Set protocol version
        Pool.setProtocolVersion(PROTOCOL_VERSION).get()

        // Create and Open Pool
        poolName = PoolUtils.createPoolLedgerConfig()
        pool = PoolUtils.createAndOpenPoolLedger(poolName)

        // Issuer Create and Open Wallet
        Wallet.createWallet(poolName, issuerWalletName, TYPE, null, CREDENTIALS).get()
        issuerWallet = Wallet.openWallet(issuerWalletName, null, CREDENTIALS).get()

        Wallet.createWallet(poolName, issuer2WalletName, TYPE, null, CREDENTIALS).get()
        issuer2Wallet = Wallet.openWallet(issuer2WalletName, null, CREDENTIALS).get()

        // Prover Create and Open Wallet
        Wallet.createWallet(poolName, proverWalletName, TYPE, null, CREDENTIALS).get()
        proverWallet = Wallet.openWallet(proverWalletName, null, CREDENTIALS).get()
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        // Issuer Remove Wallet
        issuerWallet.closeWallet().get()
        Wallet.deleteWallet(issuerWalletName, CREDENTIALS).get()

        issuer2Wallet.closeWallet().get()
        Wallet.deleteWallet(issuer2WalletName, CREDENTIALS).get()

        // Prover Remove Wallet
        proverWallet.closeWallet().get()
        Wallet.deleteWallet(proverWalletName, CREDENTIALS).get()

        // Close pool
        pool.closePoolLedger().get()
        Pool.deletePoolLedgerConfig(poolName)

        // Cleanup
        StorageUtils.cleanupStorage()
    }

    private fun createTrusteeDid(wallet: Wallet) = Did.createAndStoreMyDid(wallet, """{"seed":"$TRUSTEE_SEED"}""").get()
    private fun createDid(wallet: Wallet) = Did.createAndStoreMyDid(wallet, "{}").get()

    private fun linkIssuerToTrustee(trusteeDid: String, issuerWallet: Wallet, issuerDidInfo: DidResults.CreateAndStoreMyDidResult) {
        LedgerService.addNym(trusteeDid, pool, issuerWallet) {
            IndyUser.IdentityDetails(issuerDidInfo.did, issuerDidInfo.verkey, null, "TRUSTEE")
        }
    }

    private fun linkProverToIssuer(issuerDid: String, issuerWallet: Wallet, proverDidInfo: DidResults.CreateAndStoreMyDidResult) {
        LedgerService.addNym(issuerDid, pool, issuerWallet) {
            IndyUser.IdentityDetails(proverDidInfo.did, proverDidInfo.verkey, null, null)
        }
    }

    @Test
    @Throws(Exception::class)
    fun `revocation works fine`() {
        val trusteeDidInfo = createTrusteeDid(issuerWallet)
        val issuerDidInfo = createDid(issuerWallet)
        linkIssuerToTrustee(trusteeDidInfo.did, issuerWallet, issuerDidInfo)

        val proverDidInfo = createDid(proverWallet)
        linkProverToIssuer(issuerDidInfo.did, issuerWallet, proverDidInfo)

        val issuer = IndyUser(pool, issuerWallet, issuerDidInfo.did)
        val prover = IndyUser(pool, proverWallet, proverDidInfo.did)

        val gvtSchema = issuer.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer.createClaimDef(gvtSchema.id, true)
        val revRegInfo = issuer.createRevocationRegistry(credDef)

        prover.createMasterSecret(masterSecretId)

        val credOffer = issuer.createClaimOffer(credDef.id)
        val credReq = prover.createClaimReq(prover.did, credOffer, masterSecretId)
        val claimInfo = issuer.issueClaim(credReq, gvtCredentialValues, credOffer, revRegInfo.definition.id)
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

        assertEquals("Alex", proof.proofData.requestedProof.revealedAttrs["name"]!!.raw)
        assertTrue(IndyUser.verifyProof(DID_MY1, pool, proofReq, proof))

        issuer.revokeClaim(claimInfo.claim.revRegId!!, claimInfo.credRevocId!!)
        Thread.sleep(3000)

        val proofReqAfterRevocation = IndyUser.createProofRequest(
                attributes = listOf(field_name, field_sex),
                predicates = listOf(CredPredicate(field_age, 18)),
                nonRevoked = Interval.recent()
        )
        val proofAfterRevocation = prover.createProof(proofReqAfterRevocation, masterSecretId)

        assertFalse(IndyUser.verifyProof(DID_MY1, pool, proofReqAfterRevocation, proofAfterRevocation))
    }

    @Test
    @Throws(Exception::class)
    fun `1 issuer 1 prover 1 claim setup works fine`() {
        val trusteeDidInfo = createTrusteeDid(issuerWallet)
        val issuerDidInfo = createDid(issuerWallet)
        linkIssuerToTrustee(trusteeDidInfo.did, issuerWallet, issuerDidInfo)

        val proverDidInfo = createDid(proverWallet)
        linkProverToIssuer(issuerDidInfo.did, issuerWallet, proverDidInfo)

        val issuer = IndyUser(pool, issuerWallet, issuerDidInfo.did)
        val prover = IndyUser(pool, proverWallet, proverDidInfo.did)

        val gvtSchema = issuer.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer.createClaimDef(gvtSchema.id, false)

        prover.createMasterSecret(masterSecretId)

        val credOffer = issuer.createClaimOffer(credDef.id)
        val credReq = prover.createClaimReq(prover.did, credOffer, masterSecretId)
        val claimInfo = issuer.issueClaim(credReq, gvtCredentialValues, credOffer, null)
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

        assertEquals("Alex", proof.proofData.requestedProof.revealedAttrs["name"]!!.raw)
        assertTrue(IndyUser.verifyProof(DID_MY1, pool, proofReq, proof))
    }

    @Test
    @Throws(Exception::class)
    fun `2 issuers 1 prover 2 claims setup works fine`() {
        val trusteeDidInfo = createTrusteeDid(issuerWallet)
        val issuerDidInfo = createDid(issuerWallet)
        linkIssuerToTrustee(trusteeDidInfo.did, issuerWallet, issuerDidInfo)

        val issuer2DidInfo = createDid(issuer2Wallet)
        linkIssuerToTrustee(trusteeDidInfo.did, issuerWallet, issuer2DidInfo)

        val proverDidInfo = createDid(proverWallet)
        linkProverToIssuer(issuerDidInfo.did, issuerWallet, proverDidInfo)

        val issuer1 = IndyUser(pool, issuerWallet, issuerDidInfo.did)
        val issuer2 = IndyUser(pool, issuer2Wallet, issuer2DidInfo.did)
        val prover = IndyUser(pool, proverWallet, proverDidInfo.did)

        val schema1 = issuer1.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef1 = issuer1.createClaimDef(schema1.id, false)

        val schema2 = issuer2.createSchema(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val credDef2 = issuer2.createClaimDef(schema2.id, false)

        prover.createMasterSecret(masterSecretId)

        val gvtCredOffer = issuer1.createClaimOffer(credDef1.id)
        val xyzCredOffer = issuer2.createClaimOffer(credDef2.id)

        val gvtCredReq = prover.createClaimReq(prover.did, gvtCredOffer, masterSecretId)
        val gvtCredential = issuer1.issueClaim(gvtCredReq, gvtCredentialValues, gvtCredOffer, null)
        prover.receiveClaim(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createClaimReq(prover.did, xyzCredOffer, masterSecretId)
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

        assertTrue(IndyUser.verifyProof(DID_MY1, pool, proofReq, proof))
    }

    @Test
    @Throws(Exception::class)
    fun `1 issuer 1 prover 2 claims setup works fine`() {
        val trusteeDidInfo = createTrusteeDid(issuerWallet)
        val issuerDidInfo = createDid(issuerWallet)
        linkIssuerToTrustee(trusteeDidInfo.did, issuerWallet, issuerDidInfo)

        val proverDidInfo = createDid(proverWallet)
        linkProverToIssuer(issuerDidInfo.did, issuerWallet, proverDidInfo)

        val issuer = IndyUser(pool, issuerWallet, issuerDidInfo.did)
        val prover = IndyUser(pool, proverWallet, proverDidInfo.did)

        val gvtSchema = issuer.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val gvtCredDef = issuer.createClaimDef(gvtSchema.id, false)

        val xyzSchema = issuer.createSchema(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val xyzCredDef = issuer.createClaimDef(xyzSchema.id, false)

        prover.createMasterSecret(masterSecretId)

        val gvtCredOffer = issuer.createClaimOffer(gvtCredDef.id)
        val xyzCredOffer = issuer.createClaimOffer(xyzCredDef.id)

        val gvtCredReq = prover.createClaimReq(prover.did, gvtCredOffer, masterSecretId)
        val gvtCredential = issuer.issueClaim(gvtCredReq, gvtCredentialValues, gvtCredOffer)
        prover.receiveClaim(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createClaimReq(prover.did, xyzCredOffer, masterSecretId)
        val xyzCredential = issuer.issueClaim(xyzCredReq, xyzCredentialValues, xyzCredOffer)
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

        assertTrue(IndyUser.verifyProof(DID_MY1, pool, proofReq, proof))
    }
}
