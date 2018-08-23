package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolUtils
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
    private val xyzCredentialValues = """{"status":{"raw":"partial","encoded":"51792877103171595686471452153480627530895"},"period":{"raw":"8","encoded":"8"}}"""

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

        val credDef = issuer.createClaimDef(gvtSchema.id, true)

        val from = Timestamp.now()

        val revRegInfo = issuer.createRevocationRegistry(credDef)

        prover.createMasterSecret(masterSecretId)

        val credOffer = issuer.createClaimOffer(credDef.id)

        val credReq = prover.createClaimReq(prover.did, credOffer, masterSecretId)

        val claimInfo = issuer.issueClaim(credReq, gvtCredentialValues, credOffer, revRegInfo.definition.id)

        prover.receiveClaim(claimInfo, credReq, credOffer)

        val field_name = CredFieldRef("name", gvtSchema.id, credDef.id)
        val field_sex = CredFieldRef("sex", gvtSchema.id, credDef.id)
//        val field_phone = IndyUser.CredFieldRef("phone", gvtSchema.id, credDef.id)
        val field_age = CredFieldRef("age", gvtSchema.id, credDef.id)

        val to = Timestamp.now()

        val proofReq = prover.createProofRequest(
                attributes = listOf(field_name, field_sex),
                predicates = listOf(CredPredicate(field_age, 18)),
                nonRevoked = Interval(from, to)
        )
        val proof = prover.createProof(proofReq, masterSecretId)

        assertEquals("Alex", proof.proofData.requestedProof.revealedAttrs["name"]!!.raw)

//        assertNotNull(proof.json.getJSONObject("requested_proof").getJSONObject("unrevealed_attrs").getJSONObject("attr1_referent").getInt("sub_proof_index"))

//        assertEquals("8-800-300", proof.json.getJSONObject("requested_proof").getJSONObject("self_attested_attrs").getString("attr2_referent"))

        assertTrue(issuer.verifyProof(proofReq, proof))
    }

/*    @Test
    @Throws(Exception::class)
    fun testAnoncredsWorksForMultipleIssuerSingleProver() {
        val gvtIssuer = IndyUser(pool, issuerWallet, issuerDid, TRUSTEE_IDENTITY_JSON)

        Wallet.createWallet(poolName, "issuer2Wallet", "default", null, CREDENTIALS).get()
        val issuerXyzWallet = Wallet.openWallet("issuer2Wallet", null, CREDENTIALS).get()
        val xyzIssuer = IndyUser(pool, issuerXyzWallet, "VsKV7grR1BUE29mG2Fm2kX", TRUSTEE_IDENTITY_JSON)

        val prover = IndyUser(pool, proverWallet, proverDid, TRUSTEE_IDENTITY_JSON)


        val gvtSchema = gvtIssuer.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val gvtCredDef = gvtIssuer.createClaimDef(gvtSchema.id)

        val xyzSchema = xyzIssuer.createSchema(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val xyzCredDef = xyzIssuer.createClaimDef(xyzSchema.id)

        val gvtRevRegInfo = gvtIssuer.createRevocationRegistry(gvtCredDef)
        val xyzRevRegInfo = xyzIssuer.createRevocationRegistry(xyzCredDef)

        prover.createMasterSecret(masterSecretId)

        val gvtCredOffer = gvtIssuer.createClaimOffer(gvtCredDef.id)
        val xyzCredOffer = xyzIssuer.createClaimOffer(xyzCredDef.id)

        val gvtCredReq = prover.createClaimReq(prover.did, gvtCredOffer, masterSecretId)
        val gvtCredential = gvtIssuer.issueClaim(gvtCredReq, gvtCredentialValues, gvtCredOffer, gvtRevRegInfo.definition.id)
        prover.receiveClaim(gvtCredential, gvtCredReq, gvtCredOffer, gvtRevRegInfo.definition.id)


        val xyzCredReq = prover.createClaimReq(prover.did, xyzCredOffer, masterSecretId)
        val xyzCredential = xyzIssuer.issueClaim(xyzCredReq, xyzCredentialValues, xyzCredOffer, xyzRevRegInfo.definition.id)
        prover.receiveClaim(xyzCredential, xyzCredReq, xyzCredOffer, xyzRevRegInfo.definition.id)


        val field_name = CredFieldRef("name", gvtSchema.id, gvtCredDef.id)
        val field_age = CredFieldRef("age", gvtSchema.id, gvtCredDef.id)
        val field_status = CredFieldRef("status", xyzSchema.id, xyzCredDef.id)
        val field_period = CredFieldRef("period", xyzSchema.id, xyzCredDef.id)

        val proofReq = prover.createProofReq(
                listOf(field_name, field_status),
                listOf(CredPredicate(field_age, 18), CredPredicate(field_period, 5)),
                null //Interval.recent()
        )

        val proof = prover.createProof(proofReq, masterSecretId)

        // Verifier verify Proof
        val revealedAttr0 = proof.proofData.requestedProof.revealedAttrs["name"]!!
        assertEquals("Alex", revealedAttr0.raw)

        val revealedAttr1 = proof.proofData.requestedProof.revealedAttrs["status"]!!
        assertEquals("partial", revealedAttr1.raw)

        assertTrue(IndyUser.verifyProof(proofReq, proof))

        // Close and delete Issuer2 Wallet
        issuerXyzWallet.closeWallet().get()
        Wallet.deleteWallet("issuer2Wallet", CREDENTIALS).get()
    }*/

    /*@Test
    @Throws(Exception::class)
    fun testAnoncredsWorksForSingleIssuerSingleProverMultipleCredentials() {
        val issuer = IndyUser(pool, issuerWallet, issuerDid, TRUSTEE_IDENTITY_JSON)
        val prover = IndyUser(pool, proverWallet, proverDid, TRUSTEE_IDENTITY_JSON)

        val gvtSchema = issuer.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val gvtCredDef = issuer.createClaimDef(gvtSchema.id)

        val xyzSchema = issuer.createSchema(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val xyzCredDef = issuer.createClaimDef(xyzSchema.id)

        prover.createMasterSecret(masterSecretId)

        val gvtCredOffer = issuer.createClaimOffer(gvtCredDef.id)
        val xyzCredOffer = issuer.createClaimOffer(xyzCredDef.id)

        val gvtCredReq = prover.createClaimReq(prover.did, gvtCredOffer, masterSecretId)
        val gvtCredential = issuer.issueClaim(gvtCredReq, gvtCredentialValues, gvtCredOffer)
        prover.receiveClaim(gvtCredential.claim, gvtCredReq, gvtCredOffer)


        val xyzCredReq = prover.createClaimReq(prover.did, xyzCredOffer, masterSecretId)
        val xyzCredential = issuer.issueClaim(xyzCredReq, xyzCredentialValues, xyzCredOffer)
        prover.receiveClaim(xyzCredential.claim, xyzCredReq, xyzCredOffer)

        val field_name = CredFieldRef("name", gvtSchema.id, gvtCredDef.id)
        val field_age = CredFieldRef("age", gvtSchema.id, gvtCredDef.id)
        val field_status = CredFieldRef("status", xyzSchema.id, xyzCredDef.id)
        val field_period = CredFieldRef("period", xyzSchema.id, xyzCredDef.id)

        val proofReq = prover.createProofReq(listOf(field_name, field_status), listOf(CredPredicate(field_age, 18), CredPredicate(field_period, 5)))

        val proof = prover.createProof(proofReq, masterSecretId)


        // Verifier verify Proof
        val revealedAttr0 = proof.proofData.requestedProof.revealedAttrs["attr0_referent"]!!
        assertEquals("Alex", revealedAttr0.raw)

        val revealedAttr1 = proof.proofData.requestedProof.revealedAttrs["attr1_referent"]!!
        assertEquals("partial", revealedAttr1.raw)


        assertTrue(IndyUser.verifyProof(proofReq, proof))
    }

*//*
    @Test
    @Throws(Exception::class)
    fun testAnoncredsWorksForRevocationProof() {

        // Issuer create Schema
        val createSchemaResult = Anoncreds.issuerCreateSchema(issuerDid, GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES).get()
        val gvtSchemaId = createSchemaResult.schemaId
        val schemaJson = createSchemaResult.schemaJson

        // Issuer create credential definition
        val revocationCredentialDefConfig = "{\"support_revocation\":true}"
        val createCredentialDefResult = Anoncreds.issuerCreateAndStoreCredentialDef(issuerWallet!!, issuerDid, schemaJson, TAG, null, revocationCredentialDefConfig).get()
        val credDefId = createCredentialDefResult.credDefId
        val credDef = createCredentialDefResult.credDefJson

        // Issuer create revocation registry
        val revRegConfig = JSONObject("{\"issuance_type\":null,\"max_cred_num\":5}").toString()
        val tailsWriterConfig = JSONObject(String.format("{\"base_dir\":\"%s\", \"uri_pattern\":\"\"}", getIndyHomePath("tails")).replace('\\', '/')).toString()
        val tailsWriter = BlobStorageWriter.openWriter("default", tailsWriterConfig).get()

        val createRevRegResult = Anoncreds.issuerCreateAndStoreRevocReg(issuerWallet!!, issuerDid, null, TAG, credDefId, revRegConfig, tailsWriter).get()
        val revRegId = createRevRegResult.revRegId
        val revRegDef = createRevRegResult.revRegDefJson

        // Prover create Master Secret
        Anoncreds.proverCreateMasterSecret(proverWallet!!, masterSecretId).get()

        // Issuer create Credential Offer
        val credOffer = Anoncreds.issuerCreateCredentialOffer(issuerWallet!!, credDefId).get()

        // Prover create Credential Request
        val createCredReqResult = Anoncreds.proverCreateCredentialReq(proverWallet!!, proverDid, credOffer, credDef, masterSecretId).get()
        val credReq = createCredReqResult.credentialRequestJson
        val credReqMetadata = createCredReqResult.credentialRequestMetadataJson

        // Issuer open TailsReader
        val blobStorageReaderCfg = BlobStorageReader.openReader("default", tailsWriterConfig).get()
        val blobStorageReaderHandleCfg = blobStorageReaderCfg.blobStorageReaderHandle

        // Issuer create Credential
        val createCredentialResult = Anoncreds.issuerCreateCredential(issuerWallet!!, credOffer, credReq, gvtCredentialValues, revRegId, blobStorageReaderHandleCfg).get()
        val credential = createCredentialResult.credentialJson
        val revRegDelta = createCredentialResult.revocRegDeltaJson
        val credRevId = createCredentialResult.revocId

        // Prover store received Credential
        Anoncreds.proverStoreCredential(proverWallet!!, credentialId1, credReqMetadata, credential, credDef, revRegDef).get()

        // Prover gets Credentials for Proof Request
        val proofRequest = JSONObject("{\n" +
                "                   \"nonce\":\"123432421212\",\n" +
                "                   \"name\":\"proof_req_1\",\n" +
                "                   \"version\":\"0.1\", " +
                "                   \"requested_attributes\":{" +
                "                          \"attr1_referent\":{\"name\":\"name\"}" +
                "                    },\n" +
                "                    \"requested_predicates\":{" +
                "                          \"predicate1_referent\":{\"name\":\"age\",\"p_type\":\">=\",\"p_value\":18}" +
                "                    }" +
                "               }").toString()

        val credentialsJson = Anoncreds.proverGetCredentialsForProofReq(proverWallet!!, proofRequest).get()
        val credentials = JSONObject(credentialsJson)
        val credentialsForAttr1 = credentials.getJSONObject("attrs").getJSONArray("attr1_referent")

        val credentialUuid = credentialsForAttr1.getJSONObject(0).getJSONObject("cred_info").getString("referent")

        // Prover create RevocationState
        val timestamp = 100
        val revStateJson = Anoncreds.createRevocationState(blobStorageReaderHandleCfg, revRegDef, revRegDelta, timestamp, credRevId).get()


        // Prover create Proof
        val requestedCredentialsJson = JSONObject(String.format("{" +
                "\"self_attested_attributes\":{}," +
                "\"requested_attributes\":{\"attr1_referent\":{\"cred_id\":\"%s\", \"revealed\":true, \"timestamp\":%d }}," +
                "\"requested_predicates\":{\"predicate1_referent\":{\"cred_id\":\"%s\", \"timestamp\":%d}}" +
                "}", credentialUuid, timestamp, credentialUuid, timestamp)).toString()

        val schemas = JSONObject(String.format("{\"%s\":%s}", gvtSchemaId, schemaJson)).toString()
        val credentialDefs = JSONObject(String.format("{\"%s\":%s}", credDefId, credDef)).toString()
        val revStates = JSONObject(String.format("{\"%s\": { \"%s\":%s }}", revRegId, timestamp, revStateJson)).toString()

        val proofJson = Anoncreds.proverCreateProof(proverWallet!!, proofRequest, requestedCredentialsJson, masterSecretId, schemas,
                credentialDefs, revStates).get()
        val proof = JSONObject(proofJson)

        // Verifier verify proof
        val revealedAttr1 = proof.getJSONObject("requested_proof").getJSONObject("revealed_attrs").getJSONObject("attr1_referent")
        assertEquals("Alex", revealedAttr1.getString("raw"))

        val revRegDefs = JSONObject(String.format("{\"%s\":%s}", revRegId, revRegDef)).toString()
        val revRegs = JSONObject(String.format("{\"%s\": { \"%s\":%s }}", revRegId, timestamp, revRegDelta)).toString()

        val valid = Anoncreds.verifierVerifyProof(proofRequest, proofJson, schemas, credentialDefs, revRegDefs, revRegs).get()
        assertTrue(valid)
    }
*//*

    @Test
    @Throws(Exception::class)
    fun testVerifyProofWorksForProofDoesNotCorrespondToProofRequest() {

//        thrown.expect(ExecutionException::class.java)
//        thrown.expectCause(isA<T>(InvalidStructureException::class.java))

        val issuer = IndyUser(pool, issuerWallet, issuerDid, TRUSTEE_IDENTITY_JSON)
        val prover = IndyUser(pool, proverWallet, proverDid, TRUSTEE_IDENTITY_JSON)

        val gvtSchema = issuer.createSchema(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer.createClaimDef(gvtSchema.id)

        prover.createMasterSecret(masterSecretId)

        val credOffer = issuer.createClaimOffer(credDef.id)

        val credReq = prover.createClaimReq(prover.did, credOffer, masterSecretId)

        val credential = issuer.issueClaim(credReq, gvtCredentialValues, credOffer)

        prover.receiveClaim(credential.claim, credReq, credOffer)

        val field_name = CredFieldRef("name", gvtSchema.id, credDef.id)
        val field_sex = CredFieldRef("sex", gvtSchema.id, credDef.id)
        val field_phone = CredFieldRef("phone", gvtSchema.id, credDef.id)
        val field_age = CredFieldRef("age", gvtSchema.id, credDef.id)

        val proofReq = prover.createProofReq(listOf(field_name, field_sex), listOf())

        val proof = prover.createProof(proofReq, masterSecretId)





//        // Prover gets Credentials for Proof Request
//        var proofRequestJson = JSONObject(String.format("{" +
//                "                    \"nonce\":\"123432421212\",\n" +
//                "                    \"name\":\"proof_req_1\",\n" +
//                "                    \"version\":\"0.1\", " +
//                "                    \"requested_attrs\": {" +
//                "                          \"attr1_referent\":{ \"name\":\"name\", \"restrictions\":[{\"schema_id\":\"%s\"}]}," +
//                "                          \"attr2_referent\":{ \"name\":\"phone\"}" +
//                "                     }," +
//                "                    \"requested_predicates\":{}" +
//                "                  }", gvtSchemaId)).toString()


        // Prover create Proof


        // Verifier verify Proof
        val revealedAttr0 = proof.proofData.requestedProof.revealedAttrs["attr0_referent"]!!
        assertEquals("Alex", revealedAttr0.raw)

//        assertEquals("8-800-300", proof.json.getJSONObject("requested_proof").getJSONObject("self_attested_attrs").getString("attr2_referent"))


        IndyUser.verifyProof(proofReq, proof)
    }*/
}
