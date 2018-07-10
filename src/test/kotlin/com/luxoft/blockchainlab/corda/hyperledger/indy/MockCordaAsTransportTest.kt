package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.natpryce.konfig.Configuration
import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.TestConfigurationsProvider
import net.corda.node.internal.StartedNode
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.startFlow
import org.junit.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.Duration
import java.util.*


class MockCordaAsTransportTest {

    private lateinit var net: InternalMockNetwork
    private lateinit var notary: StartedNode<MockNode>
    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>

    private lateinit var parties: List<StartedNode<MockNode>>

    companion object {

        @JvmStatic
        @BeforeClass
        fun init() {
            //setCordappPackages("com.luxoft.blockchainlab.corda.hyperledger.indy")
        }

        @JvmStatic
        @AfterClass
        fun shutdown() {
            //unsetCordappPackages()
        }
    }

    @Before
    fun setup() {

        setupIndyConfigs()

        net = InternalMockNetwork(listOf("com.luxoft.blockchainlab.corda.hyperledger.indy"))

        notary = net.defaultNotaryNode

        issuer = net.createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = net.createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = net.createPartyNode(CordaX500Name("Bob", "London", "GB"))

        parties = listOf(issuer, alice, bob)

        parties.forEach {
            it.registerInitiatedFlow(AssignPermissionsFlow.Authority::class.java)
            it.registerInitiatedFlow(CreatePairwiseFlow.Issuer::class.java)
            it.registerInitiatedFlow(IssueClaimFlow.Prover::class.java)
            it.registerInitiatedFlow(VerifyClaimFlow.Prover::class.java)
        }
//        net.registerIdentities()
    }

    private fun setupIndyConfigs() {

        TestConfigurationsProvider.provider = object : TestConfigurationsProvider {
            override fun getConfig(name: String): Configuration? {
                // Watch carefully for these hard-coded values
                // Now we assume that issuer(indy trustee) is the first created node from SomeNodes
                return if (name == "Issuer") {
                    ConfigurationMap(mapOf(
                            "indyuser.walletName" to name,
                            "indyuser.role" to "trustee",
                            "indyuser.did" to "V4SGRU86Z58d6TV7PBUe6f",
                            "indyuser.seed" to "000000000000000000000000Trustee1"
                    ))
                } else ConfigurationMap(mapOf(
                        "indyuser.walletName" to name + System.currentTimeMillis().toString()
                ))
            }
        }
    }

    @After
    fun tearDown() {
        issuer.services.cordaService(IndyService::class.java).indyUser.close()
        alice.services.cordaService(IndyService::class.java).indyUser.close()
        bob.services.cordaService(IndyService::class.java).indyUser.close()
        net.stopNodes()
    }


    private fun setPermissions(issuer: StartedNode<MockNode>,
                               authority: StartedNode<MockNode>) {
        val permissionsFuture = issuer.services.startFlow(AssignPermissionsFlow.Issuer(
                authority = authority.info.singleIdentity().name)).resultFuture

        net.runNetwork()
        permissionsFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueSchemaAndClaimDef(schemaOwner: StartedNode<MockNode>,
                                       claimDefOwner: StartedNode<MockNode>,
                                       schema: Schema): Pair<String, String> {

        val schemaOwnerDid = schemaOwner.services.cordaService(IndyService::class.java).indyUser.did
        val schemaFuture = schemaOwner.services.startFlow(
                CreateSchemaFlow.Authority(
                        schema.getSchemaName(),
                        schema.getSchemaVersion(),
                        schema.getSchemaAttrs())).resultFuture

        net.runNetwork()
        val schemaId = schemaFuture.getOrThrow(Duration.ofSeconds(30))

        val claimDefFuture = claimDefOwner.services.startFlow(
                CreateClaimDefFlow.Authority(schemaId)).resultFuture

        net.runNetwork()
        val credDefId = claimDefFuture.getOrThrow(Duration.ofSeconds(30))

        return Pair(schemaId, credDefId)
    }

    private fun issueClaim(claimProver: StartedNode<MockNode>,
                           claimIssuer: StartedNode<MockNode>,
                           schemaOwner: StartedNode<MockNode>,
                           claimProposal: String,
                           schema: Schema,
                           credDefId: String) {
        val identifier = UUID.randomUUID().toString()

        val schemaOwnerDid = schemaOwner.services.cordaService(IndyService::class.java).indyUser.did

        val schemaDetails = IndyUser.SchemaDetails(
                schema.getSchemaName(),
                schema.getSchemaVersion(),
                schemaOwnerDid)

        val claimFuture = claimIssuer.services.startFlow(
                IssueClaimFlow.Issuer(
                        identifier,
                        schemaDetails,
                        credDefId,
                        claimProposal,
                        claimProver.info.singleIdentity().name)
        ).resultFuture

        net.runNetwork()
        claimFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun verifyClaim(verifier: StartedNode<MockNode>,
                            prover: StartedNode<MockNode>,
                            attributes: List<IndyUser.ProofAttribute>,
                            predicates: List<IndyUser.ProofPredicate>,
                            assertion: (actual: Boolean) -> Unit) {
        val identifier = UUID.randomUUID().toString()

        val proofCheckResultFuture = verifier.services.startFlow(
                VerifyClaimFlow.Verifier(
                        identifier,
                        attributes,
                        predicates,
                        prover.info.singleIdentity().name)).resultFuture

        net.runNetwork()
        assertion(proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30)))
    }

    private fun multipleClaimsByDiffIssuers(attrs: Map<String, String>,
                                            preds: Map<String, String>,
                                            assertion: (actual: Boolean) -> Unit) {

        // Request permissions from trustee to write on ledger
        setPermissions(bob, issuer)

        // Issue schemas and claimDefs
        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val (schemaId, credDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        val (eduSchemaId, eduCredDefId) = issueSchemaAndClaimDef(issuer, bob, schemaEducation)

        // Issue claim #1
        var claimProposal = String.format(schemaPerson.getSchemaProposal(),
                attrs.map{ it.key }[0], "119191919", preds.map{ it.key }[0], preds.map{ it.key }[0])

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, credDefId)

        // Issue claim #2
        claimProposal = String.format(schemaEducation.getSchemaProposal(),
                attrs.map{ it.key }[1], "119191918", preds.map{ it.key }[1], preds.map{ it.key }[1])

        issueClaim(alice, bob, issuer, claimProposal, schemaEducation, eduCredDefId)

        // Verify claims
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaPersonDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)
        val schemaEducationDetails = IndyUser.SchemaDetails(schemaEducation.getSchemaName(), schemaEducation.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaPersonDetails, credDefId, schemaPerson.schemaAttr1, attrs.map{ it.value }[0]),
                IndyUser.ProofAttribute(schemaEducationDetails, eduCredDefId, schemaEducation.schemaAttr1, attrs.map{ it.value }[1])
        )

        val predicates = listOf(
                IndyUser.ProofPredicate(schemaPersonDetails, credDefId, schemaPerson.schemaAttr2,  preds.map{ it.value }[0].toInt()),
                IndyUser.ProofPredicate(schemaEducationDetails, eduCredDefId, schemaEducation.schemaAttr2, preds.map{ it.value }[1].toInt())
        )

        verifyClaim(bob, alice, attributes, predicates, assertion)
    }

    @Test
    fun validMultipleClaimsByDiffIssuers() {
        val attributes = mapOf(
                "John Smith" to "John Smith",
                "University" to "University")
        val predicates = mapOf(
                "1988" to "1978",
                "2016" to "2006")

        multipleClaimsByDiffIssuers(attributes, predicates, { res -> assertTrue(res) })
    }

    @Test
    fun `invalid predicates of multiple claims issued by diff authorities`() {
        val attributes = mapOf(
                "John Smith" to "John Smith",
                "University" to "University")
        val predicates = mapOf(
                "1988" to "1978",
                "2016" to "2026")

        multipleClaimsByDiffIssuers(attributes, predicates, { res -> assertFalse(res) })
    }

    @Test
    fun `invalid attributes of multiple claims issued by diff authorities`() {
        val attributes = mapOf(
                "John Smith" to "Vanga",
                "University" to "University")
        val predicates = mapOf(
                "1988" to "1978",
                "2016" to "2006")

        multipleClaimsByDiffIssuers(attributes, predicates, { res -> assertFalse(res) })
    }

    @Test
    fun singleClaimLifecycle() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val (schemaId, credDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = String.format(schemaPerson.getSchemaProposal(),
                "John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, credDefId)

        // Verify claim
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaDetails, credDefId, schemaPerson.schemaAttr1, "John Smith"))

        val predicates = listOf(
                // -10 to check >=
                IndyUser.ProofPredicate(schemaDetails, credDefId, schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10))

        verifyClaim(bob, alice, attributes, predicates, { res -> assertTrue(res) })
    }

    @Test
    fun multipleClaimsBySameIssuer() {

        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val (schemaId, credDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        val (eduSchemaId, eduCredDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaEducation)

        // Issue claim #1
        val schemaPersonAttrInt = "1988"
        var claimProposal = String.format(schemaPerson.getSchemaProposal(), "John Smith", "119191919",
                schemaPersonAttrInt, schemaPersonAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, credDefId)

        // Issue claim #2
        val schemaEducationAttrInt = "2016"
        claimProposal = String.format(schemaEducation.getSchemaProposal(), "University", "119191918",
                schemaEducationAttrInt, schemaEducationAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaEducation, eduCredDefId)

        // Verify claims
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaPersonDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)
        val schemaEducationDetails = IndyUser.SchemaDetails(schemaEducation.getSchemaName(), schemaEducation.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaPersonDetails, credDefId, schemaPerson.schemaAttr1, "John Smith"),
                IndyUser.ProofAttribute(schemaEducationDetails,  eduCredDefId, schemaEducation.schemaAttr1, "University")
        )

        val predicates = listOf(
                // -10 to check >=
                IndyUser.ProofPredicate(schemaPersonDetails, credDefId, schemaPerson.schemaAttr2, schemaPersonAttrInt.toInt() - 10),
                IndyUser.ProofPredicate(schemaEducationDetails, eduCredDefId, schemaEducation.schemaAttr2, schemaEducationAttrInt.toInt() - 10))

        verifyClaim(bob, alice, attributes, predicates, { res -> assertTrue(res) })
    }

    @Test
    fun `empty Predicates on verification for single issued claim`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val (schemaId, credDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = String.format(schemaPerson.getSchemaProposal(),
                "John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, credDefId)

        // Verify claim
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaDetails, credDefId, schemaPerson.schemaAttr1, "John Smith")
        )

        verifyClaim(bob, alice, attributes, emptyList(), { res -> assertTrue(res) })
    }

    @Test
    fun `not all attributes to verify`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val (schemaId, credDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = String.format(schemaPerson.getSchemaProposal(),
                "John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, credDefId)

        // Verify claim
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaDetails, credDefId, schemaPerson.schemaAttr1, "John Smith"),
                IndyUser.ProofAttribute(schemaDetails, credDefId, schemaPerson.schemaAttr2, "")
        )

        verifyClaim(bob, alice, attributes, emptyList(), { res -> assertTrue(res) })
    }
}