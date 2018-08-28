package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyArtifactsRegistry
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.Interval
import com.luxoft.blockchainlab.hyperledger.indy.SchemaDetails
import com.natpryce.konfig.Configuration
import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.TestConfigurationsProvider
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import net.corda.testing.node.internal.startFlow
import org.junit.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.Duration
import java.util.*


class CordentityE2E {

    private lateinit var net: InternalMockNetwork
    private lateinit var notary: StartedNode<MockNode>
    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>
    private lateinit var artifactory: StartedNode<MockNode>

    private lateinit var parties: List<StartedNode<MockNode>>

    companion object {

        @JvmStatic
        @BeforeClass
        fun init() {
            //setCordappPackages("com.luxoft.blockchainlab.corda.hyperledger.indy")
//            Pool.setProtocolVersion(2).get()
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

        net = InternalMockNetwork(
                cordappPackages = listOf("com.luxoft.blockchainlab.corda.hyperledger.indy"),
                networkParameters = testNetworkParameters(maxTransactionSize = 10485760 * 5))

        notary = net.defaultNotaryNode

        issuer = net.createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = net.createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = net.createPartyNode(CordaX500Name("Bob", "London", "GB"))
        artifactory = net.createPartyNode(CordaX500Name("Artifactory", "London", "GB"))

        parties = listOf(issuer, alice, bob)

        parties.forEach {
            it.registerInitiatedFlow(AssignPermissionsFlow.Authority::class.java)
            it.registerInitiatedFlow(CreatePairwiseFlow.Issuer::class.java)
            it.registerInitiatedFlow(IssueClaimFlow.Prover::class.java)
            it.registerInitiatedFlow(VerifyClaimFlow.Prover::class.java)
            it.registerInitiatedFlow(RevokeClaimFlow.Prover::class.java)
        }

        artifactory.registerInitiatedFlow(IndyArtifactsRegistry.QueryHandler::class.java)
        artifactory.registerInitiatedFlow(IndyArtifactsRegistry.CheckHandler::class.java)
        artifactory.registerInitiatedFlow(IndyArtifactsRegistry.PutHandler::class.java)
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
        try {
            issuer.services.cordaService(IndyService::class.java).indyUser.close()
            alice.services.cordaService(IndyService::class.java).indyUser.close()
            bob.services.cordaService(IndyService::class.java).indyUser.close()
            artifactory.services.cordaService(IndyService::class.java).indyUser.close()
        } finally {
            net.stopNodes()
        }
    }


    private fun setPermissions(issuer: StartedNode<MockNode>,
                               authority: StartedNode<MockNode>) {
        val permissionsFuture = issuer.services.startFlow(AssignPermissionsFlow.Issuer(
                authority = authority.info.singleIdentity().name, role = "TRUSTEE")).resultFuture

        net.runNetwork()
        permissionsFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueSchemaAndClaimDef(schemaOwner: StartedNode<MockNode>,
                                       claimDefOwner: StartedNode<MockNode>,
                                       schema: Schema): CreateClaimDefFlowResult {

        // create schema
        val schemaFuture = schemaOwner.services.startFlow(
                CreateSchemaFlow.Authority(
                        schema.schemaName,
                        schema.schemaVersion,
                        schema.schemaAttrs,
                        artifactory.getName()
                )
        ).resultFuture

        net.runNetwork()
        val schemaId = schemaFuture.getOrThrow(Duration.ofSeconds(30))

        // create credential definition
        val schemaDetails = SchemaDetails(
                schema.schemaName,
                schema.schemaVersion,
                schemaOwner.getPartyDid()
        )

        val claimDefFuture = claimDefOwner.services.startFlow(
                CreateClaimDefFlow.Authority(schemaDetails, 100, artifactory.getName())
        ).resultFuture

        net.runNetwork()
        return claimDefFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueClaim(claimProver: StartedNode<MockNode>,
                           claimIssuer: StartedNode<MockNode>,
                           schemaOwner: StartedNode<MockNode>,
                           claimProposal: String,
                           schema: Schema,
                           revRegId: String): String {

        val identifier = UUID.randomUUID().toString()

        val schemaOwnerDid = schemaOwner.getPartyDid()

        val schemaDetails = SchemaDetails(
                schema.schemaName,
                schema.schemaVersion,
                schemaOwnerDid
        )

        val claimFuture = claimIssuer.services.startFlow(
                IssueClaimFlow.Issuer(
                        identifier,
                        schemaDetails,
                        claimProposal,
                        revRegId,
                        claimProver.getName(),
                        artifactory.getName()
                )
        ).resultFuture

        net.runNetwork()
        return claimFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun revokeClaim(
            issuer: StartedNode<MockNode>,
            revRegId: String,
            credRevId: String
    ) {
        val flowResult = issuer.services.startFlow(
                RevokeClaimFlow.Issuer(revRegId, credRevId)
        ).resultFuture

        net.runNetwork()

        flowResult.getOrThrow(Duration.ofSeconds(30))
    }

    private fun verifyClaim(verifier: StartedNode<MockNode>,
                            prover: StartedNode<MockNode>,
                            nonRevoked: Interval,
                            attributes: List<VerifyClaimFlow.ProofAttribute>,
                            predicates: List<VerifyClaimFlow.ProofPredicate>): Boolean {
        val identifier = UUID.randomUUID().toString()

        val proofCheckResultFuture = verifier.services.startFlow(
                VerifyClaimFlow.Verifier(
                        identifier,
                        attributes,
                        predicates,
                        nonRevoked,
                        prover.getName(),
                        artifactory.getName()
                )
        ).resultFuture

        net.runNetwork()
        return proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun multipleClaimsByDiffIssuers(attrs: Map<String, String>, preds: Map<String, String>): Boolean {

        val (attr1, attr2) = attrs.entries.toList()
        val (pred1, pred2) = preds.entries.toList()

        // Request permissions from trustee to write on ledger
        setPermissions(bob, issuer)

        // Issue schemas and claimDefs
        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val claimDefResult1 = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        val claimDefResult2 = issueSchemaAndClaimDef(issuer, bob, schemaEducation)

        // Issue claim #1
        var claimProposal = schemaPerson.formatProposal(attr1.key, "119191919", pred1.key, pred1.key)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, claimDefResult1.revRegId)

        // Issue claim #2
        claimProposal = schemaEducation.formatProposal(attr2.key, "119191918", pred2.key, pred2.key)

        issueClaim(alice, bob, issuer, claimProposal, schemaEducation, claimDefResult2.revRegId)

        // Verify claims
        val schemaOwner = issuer.getPartyDid()
        val schemaPersonDetails = SchemaDetails(schemaPerson.schemaName, schemaPerson.schemaVersion, schemaOwner)
        val schemaEducationDetails = SchemaDetails(schemaEducation.schemaName, schemaEducation.schemaVersion, schemaOwner)

        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaPersonDetails, issuer.getPartyDid(), schemaPerson.schemaAttr1, attr1.value),
                VerifyClaimFlow.ProofAttribute(schemaEducationDetails, bob.getPartyDid(), schemaEducation.schemaAttr1, attr2.value)
        )

        val predicates = listOf(
                VerifyClaimFlow.ProofPredicate(schemaPersonDetails, issuer.getPartyDid(), schemaPerson.schemaAttr2, pred1.value.toInt()),
                VerifyClaimFlow.ProofPredicate(schemaEducationDetails, bob.getPartyDid(), schemaEducation.schemaAttr2, pred2.value.toInt())
        )

        return verifyClaim(bob, alice, Interval.allTime(), attributes, predicates)
    }

    @Test
    fun `2 issuers 1 prover 2 claims setup works fine`() {
        val attributes = mapOf(
                "John Smith" to "John Smith",
                "University" to "University")
        val predicates = mapOf(
                "1988" to "1978",
                "2016" to "2006")

        val claimsVerified = multipleClaimsByDiffIssuers(attributes, predicates)
        assertTrue(claimsVerified)
    }

    @Test
    fun `2 issuers 1 prover 2 claims invalid predicates setup works fine`() {
        val attributes = mapOf(
                "John Smith" to "John Smith",
                "University" to "University")
        val predicates = mapOf(
                "1988" to "1978",
                "2016" to "2026")

        val claimsVerified = multipleClaimsByDiffIssuers(attributes, predicates)
        assertFalse(claimsVerified)
    }

    @Test
    fun `2 issuers 1 prover 2 claims invalid attributes setup works fine`() {
        val attributes = mapOf(
                "John Smith" to "Vanga",
                "University" to "University")
        val predicates = mapOf(
                "1988" to "1978",
                "2016" to "2006")

        val claimsVerified = multipleClaimsByDiffIssuers(attributes, predicates)
        assertFalse(claimsVerified)
    }

    @Test
    fun `1 claim 1 prover setup works fine`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val claimDefResult = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, claimDefResult.revRegId)

        // Verify claim
        val schemaOwner = issuer.getPartyDid()
        val schemaDetails = SchemaDetails(schemaPerson.schemaName, schemaPerson.schemaVersion, schemaOwner)

        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaDetails, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"))

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(schemaDetails, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10))

        val claimVerified = verifyClaim(bob, alice, Interval.allTime(), attributes, predicates)
        assertTrue(claimVerified)
    }

    @Test
    fun `revocation works fine`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val claimDefResult = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        val credRevId = issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, claimDefResult.revRegId)

        // Verify claim
        val schemaOwner = issuer.getPartyDid()
        val schemaDetails = SchemaDetails(schemaPerson.schemaName, schemaPerson.schemaVersion, schemaOwner)

        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaDetails, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith")
        )

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(schemaDetails, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10)
        )

        val claimVerified = verifyClaim(bob, alice, Interval.recent(), attributes, predicates)
        assertTrue(claimVerified)

        revokeClaim(issuer, claimDefResult.revRegId, credRevId)

        val claimAfterRevocationVerified = verifyClaim(bob, alice, Interval.recent(), attributes, predicates)
        assertFalse(claimAfterRevocationVerified)
    }

    @Test
    fun `2 claims 1 issuer 1 prover setup works fine`() {

        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val claimDefResult1 = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        val claimDefResult2 = issueSchemaAndClaimDef(issuer, issuer, schemaEducation)

        // Issue claim #1
        val schemaPersonAttrInt = "1988"
        var claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaPersonAttrInt, schemaPersonAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, claimDefResult1.revRegId)

        // Issue claim #2
        val schemaEducationAttrInt = "2016"
        claimProposal = schemaEducation.formatProposal("University", "119191918", schemaEducationAttrInt, schemaEducationAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaEducation, claimDefResult2.revRegId)

        // Verify claims
        val schemaOwner = issuer.getPartyDid()
        val schemaPersonDetails = SchemaDetails(schemaPerson.schemaName, schemaPerson.schemaVersion, schemaOwner)
        val schemaEducationDetails = SchemaDetails(schemaEducation.schemaName, schemaEducation.schemaVersion, schemaOwner)

        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaPersonDetails, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"),
                VerifyClaimFlow.ProofAttribute(schemaEducationDetails, issuer.getPartyDid(), schemaEducation.schemaAttr1, "University")
        )

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(schemaPersonDetails, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaPersonAttrInt.toInt() - 10),
                VerifyClaimFlow.ProofPredicate(schemaEducationDetails, issuer.getPartyDid(), schemaEducation.schemaAttr2, schemaEducationAttrInt.toInt() - 10)
        )

        val claimVerified = verifyClaim(bob, alice, Interval.allTime(), attributes, predicates)
        assertTrue(claimVerified)
    }

    @Test
    fun `1 claim 1 prover without predicates setup works fine`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val claimDefResult1 = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, claimDefResult1.revRegId)

        // Verify claim
        val schemaOwner = issuer.getPartyDid()
        val schemaDetails = SchemaDetails(schemaPerson.schemaName, schemaPerson.schemaVersion, schemaOwner)

        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaDetails, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith")
        )

        val claimVerified = verifyClaim(bob, alice, Interval.allTime(), attributes, emptyList())
        assertTrue(claimVerified)
    }

    @Test
    fun `1 claim 1 prover not all attributes to verify setup works fine`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val claimDefResult = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson, claimDefResult.revRegId)

        // Verify claim
        val schemaOwner = issuer.getPartyDid()
        val schemaDetails = SchemaDetails(schemaPerson.schemaName, schemaPerson.schemaVersion, schemaOwner)

        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaDetails, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"),
                VerifyClaimFlow.ProofAttribute(schemaDetails, issuer.getPartyDid(), schemaPerson.schemaAttr2, "")
        )

        val claimVerified = verifyClaim(bob, alice, Interval.allTime(), attributes, emptyList())
        assertTrue(claimVerified)
    }
}