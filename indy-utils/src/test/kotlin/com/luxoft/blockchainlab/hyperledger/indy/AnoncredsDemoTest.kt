package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolUtils
import net.corda.core.utilities.getOrThrow
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters
import org.hyperledger.indy.sdk.wallet.Wallet
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration

class AnoncredsDemoTest : IndyIntegrationTest() {

    private lateinit var pool: Pool
    private lateinit var issuerWallet: Wallet
    private lateinit var proverWallet: Wallet
    private lateinit var poolName: String
    private val masterSecretId = "masterSecretId"
    private val credentialId1 = "id1"
    private val credentialId2 = "id2"
    private val issuerDid = "NcYxiDXkpYi6ov5FcYDi1e"
    private val proverDid = "CnEDk9HrMnmiHXEV1WFgbVCRteYnPqsJwrTdcZaNhFVW"
    private val gvtCredentialValues = GVT_CRED_VALUES
    private val xyzCredentialValues = JSONObject("{\n" +
            "        \"status\":{\"raw\":\"partial\", \"encoded\":\"51792877103171595686471452153480627530895\"},\n" +
            "        \"period\":{\"raw\":\"8\", \"encoded\":\"8\"}\n" +
            "    }").toString()

    private val credentials = """{"key": "key"}"""

    @Before
    @Throws(Exception::class)
    fun createWallet() {
        // Set protocol version
        Pool.setProtocolVersion(PROTOCOL_VERSION).get()

        // Create and Open Pool
        poolName = PoolUtils.createPoolLedgerConfig()

        val config = PoolJSONParameters.OpenPoolLedgerJSONParameter(null, null, null)
        pool = Pool.openPoolLedger(poolName, config.toJson()).get()

        // Issuer Create and Open Wallet
        Wallet.createWallet(poolName, "issuerWallet", TYPE, null, CREDENTIALS).get()
        issuerWallet = Wallet.openWallet("issuerWallet", null, CREDENTIALS).get()

        // Prover Create and Open Wallet
        Wallet.createWallet(poolName, "proverWallet", TYPE, null, CREDENTIALS).get()
        proverWallet = Wallet.openWallet("proverWallet", null, CREDENTIALS).get()
    }

    @After
    @Throws(Exception::class)
    fun deleteWallet() {
        issuerWallet.closeWallet().get()
        Wallet.deleteWallet("issuerWallet", CREDENTIALS).get()

        proverWallet.closeWallet().get()
        Wallet.deleteWallet("proverWallet", CREDENTIALS).get()

        pool.closePoolLedger().get()
    }

    @Test
    @Throws(Exception::class)
    fun testAnoncredsDemo() {
        val issuer = IndyUser(pool, issuerWallet, issuerDid, TRUSTEE_IDENTITY_JSON)
        val prover = IndyUser(pool, proverWallet, proverDid, TRUSTEE_IDENTITY_JSON)

        val gvtSchema = issuer.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)

        val credDef = issuer.createClaimDef(gvtSchema.id)

        prover.createMasterSecret(masterSecretId)

        val credOffer = issuer.createClaimOffer(credDef.id)

        val credReq = prover.createClaimReq("???", prover.did, credOffer, masterSecretId)

        val credential = issuer.issueClaim(credReq, gvtCredentialValues, credOffer)

        prover.receiveClaim(credential, credReq, credOffer)

        val field_name = IndyUser.CredFieldRef("name", gvtSchema.id, credDef.id)
        val field_sex = IndyUser.CredFieldRef("sex", gvtSchema.id, credDef.id)
//        val field_phone = IndyUser.CredFieldRef("phone", gvtSchema.id, credDef.id)
        val field_age = IndyUser.CredFieldRef("age", gvtSchema.id, credDef.id)

        val proofReq = prover.createProofReq(listOf(field_name, field_sex), mapOf(field_age to 18))

        val proof = prover.createProof(proofReq, masterSecretId)

        assertEquals("Alex", proof.revealedAttrs["attr0_referent"]!!.getString("raw"))

//        assertNotNull(proof.json.getJSONObject("requested_proof").getJSONObject("unrevealed_attrs").getJSONObject("attr1_referent").getInt("sub_proof_index"))

//        assertEquals("8-800-300", proof.json.getJSONObject("requested_proof").getJSONObject("self_attested_attrs").getString("attr2_referent"))

        assertTrue(IndyUser.verifyProof(proofReq, proof))
    }

}
