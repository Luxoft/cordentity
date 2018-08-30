package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.Interval
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
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.*
import kotlin.math.absoluteValue


class CordentityE2E {

    private lateinit var net: InternalMockNetwork
    private lateinit var trustee: StartedNode<MockNode>
    private lateinit var notary: StartedNode<MockNode>
    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>

    private lateinit var parties: List<StartedNode<MockNode>>

    private val RD = Random()

    @Before
    fun setup() {

        setupIndyConfigs()

        net = InternalMockNetwork(
                cordappPackages = listOf("com.luxoft.blockchainlab.corda.hyperledger.indy"),
                networkParameters = testNetworkParameters(maxTransactionSize = 10485760 * 5)
        )

        notary = net.defaultNotaryNode

        trustee = net.createPartyNode(CordaX500Name("Trustee", "London", "GB"))
        issuer = net.createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = net.createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = net.createPartyNode(CordaX500Name("Bob", "London", "GB"))

        parties = listOf(issuer, alice, bob)

        parties.forEach {
            it.registerInitiatedFlow(AssignPermissionsFlow.Authority::class.java)
            it.registerInitiatedFlow(CreatePairwiseFlow.Issuer::class.java)
            it.registerInitiatedFlow(IssueClaimFlow.Prover::class.java)
            it.registerInitiatedFlow(VerifyClaimFlow.Prover::class.java)
            it.registerInitiatedFlow(RevokeClaimFlow.Prover::class.java)
        }

        // Request permissions from trustee to write on ledger
        setPermissions(issuer, trustee)
        setPermissions(bob, trustee)
    }

    private fun setupIndyConfigs() {

        TestConfigurationsProvider.provider = object : TestConfigurationsProvider {
            override fun getConfig(name: String): Configuration? {
                // Watch carefully for these hard-coded values
                // Now we assume that issuer(indy trustee) is the first created node from SomeNodes
                return if (name == "Trustee") {
                    ConfigurationMap(mapOf(
                            "indyuser.walletName" to name,
                            "indyuser.role" to "trustee",
                            "indyuser.did" to "V4SGRU86Z58d6TV7PBUe6f",
                            "indyuser.seed" to "000000000000000000000000Trustee1"
                    ))
                } else ConfigurationMap(mapOf(
                        "indyuser.walletName" to name + RD.nextLong().absoluteValue
                ))
            }
        }
    }

    @After
    fun tearDown() {
        try {
            trustee.services.cordaService(IndyService::class.java).indyUser.close()
            issuer.services.cordaService(IndyService::class.java).indyUser.close()
            alice.services.cordaService(IndyService::class.java).indyUser.close()
            bob.services.cordaService(IndyService::class.java).indyUser.close()
        } finally {
            net.stopNodes()
        }
    }

    private fun setPermissions(issuer: StartedNode<MockNode>, authority: StartedNode<MockNode>) {
        val permissionsFuture = issuer.services.startFlow(
                AssignPermissionsFlow.Issuer(authority = authority.info.singleIdentity().name, role = "TRUSTEE")
        ).resultFuture

        net.runNetwork()
        permissionsFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueSchema(schemaOwner: StartedNode<MockNode>, schema: Schema): String {
        val schemaFuture = schemaOwner.services.startFlow(
                CreateSchemaFlow.Authority(schema.schemaName, schema.schemaVersion, schema.schemaAttrs)
        ).resultFuture

        net.runNetwork()
        return schemaFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueClaimDefinition(claimDefOwner: StartedNode<MockNode>, schemaId: String): CreateClaimDefinitionFlowResult {
        val claimDefFuture = claimDefOwner.services.startFlow(
                CreateClaimDefinitionFlow.Authority(schemaId)
        ).resultFuture

        net.runNetwork()
        return claimDefFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueClaim(
            claimProver: StartedNode<MockNode>,
            claimIssuer: StartedNode<MockNode>,
            claimProposal: String,
            claimDefId: String,
            revRegId: String
    ): String {

        val identifier = UUID.randomUUID().toString()

        val claimFuture = claimIssuer.services.startFlow(
                IssueClaimFlow.Issuer(
                        identifier,
                        claimDefId,
                        claimProposal,
                        revRegId,
                        claimProver.getName()
                )
        ).resultFuture

        net.runNetwork()
        claimFuture.getOrThrow(Duration.ofSeconds(30))

        return identifier
    }

    private fun revokeClaim(
            issuer: StartedNode<MockNode>,
            claimId: String
    ) {
        val flowResult = issuer.services.startFlow(
                RevokeClaimFlow.Issuer(claimId)
        ).resultFuture

        net.runNetwork()

        flowResult.getOrThrow(Duration.ofSeconds(30))
    }

    private fun verifyClaim(
            verifier: StartedNode<MockNode>,
            prover: StartedNode<MockNode>,
            attributes: List<VerifyClaimFlow.ProofAttribute>,
            predicates: List<VerifyClaimFlow.ProofPredicate>,
            nonRevoked: Interval? = null
    ): Boolean {
        val identifier = UUID.randomUUID().toString()

        val proofCheckResultFuture = verifier.services.startFlow(
                VerifyClaimFlow.Verifier(
                        identifier,
                        attributes,
                        predicates,
                        prover.getName(),
                        nonRevoked
                )
        ).resultFuture

        net.runNetwork()
        return proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun multipleClaimsByDiffIssuers(attrs: Map<String, String>, preds: Map<String, String>): Boolean {

        val (attr1, attr2) = attrs.entries.toList()
        val (pred1, pred2) = preds.entries.toList()

        // Issue schemas and claimDefs
        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val personSchemaId = issueSchema(issuer, schemaPerson)
        val educationSchemaId = issueSchema(issuer, schemaEducation)

        val (personClaimDefId, personRevRegId) = issueClaimDefinition(issuer, personSchemaId)
        val (educationClaimDefId, educationRevRegId) = issueClaimDefinition(bob, educationSchemaId)

        // Issue claim #1
        var claimProposal = schemaPerson.formatProposal(attr1.key, "119191919", pred1.key, pred1.key)

        issueClaim(alice, issuer, claimProposal, personClaimDefId, personRevRegId)

        // Issue claim #2
        claimProposal = schemaEducation.formatProposal(attr2.key, "119191918", pred2.key, pred2.key)

        issueClaim(alice, bob, claimProposal, educationClaimDefId, educationRevRegId)

        // Verify claims
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(personSchemaId, personClaimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, attr1.value),
                VerifyClaimFlow.ProofAttribute(educationSchemaId, educationClaimDefId, bob.getPartyDid(), schemaEducation.schemaAttr1, attr2.value)
        )

        val predicates = listOf(
                VerifyClaimFlow.ProofPredicate(personSchemaId, personClaimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, pred1.value.toInt()),
                VerifyClaimFlow.ProofPredicate(educationSchemaId, educationClaimDefId, bob.getPartyDid(), schemaEducation.schemaAttr2, pred2.value.toInt())
        )

        return verifyClaim(bob, alice, attributes, predicates, Interval.allTime())
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

        // issue schema
        val schemaId = issueSchema(issuer, schemaPerson)

        // issuer claim definition
        val (claimDefId, revRegId) = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, claimDefId, revRegId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith")
        )

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10)
        )

        val claimVerified = verifyClaim(bob, alice, attributes, predicates, Interval.allTime())
        assertTrue(claimVerified)
    }

    @Test
    fun `revocation works fine`() {

        val schemaPerson = SchemaPerson()

        // issue schema
        val schemaId = issueSchema(issuer, schemaPerson)

        // issuer claim definition
        val (claimDefId, revRegId) = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        val claimId = issueClaim(alice, issuer, claimProposal, claimDefId, revRegId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith")
        )

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10)
        )

        val claimVerified = verifyClaim(bob, alice, attributes, predicates, Interval.allTime())
        assertTrue(claimVerified)

        revokeClaim(issuer, claimId)

        Thread.sleep(3000)

        val claimAfterRevocationVerified = verifyClaim(bob, alice, attributes, predicates, Interval.recent())
        assertFalse(claimAfterRevocationVerified)
    }

    @Test
    fun `2 claims 1 issuer 1 prover setup works fine`() {

        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val personSchemaId = issueSchema(issuer, schemaPerson)
        val educationSchemaId = issueSchema(issuer, schemaEducation)

        val (personClaimDefId, personRevRegId) = issueClaimDefinition(issuer, personSchemaId)
        val (educationClaimDefId, educationRevRegId) = issueClaimDefinition(issuer, educationSchemaId)

        // Issue claim #1
        val schemaPersonAttrInt = "1988"
        var claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaPersonAttrInt, schemaPersonAttrInt)

        issueClaim(alice, issuer, claimProposal, personClaimDefId, personRevRegId)

        // Issue claim #2
        val schemaEducationAttrInt = "2016"
        claimProposal = schemaEducation.formatProposal("University", "119191918", schemaEducationAttrInt, schemaEducationAttrInt)

        issueClaim(alice, issuer, claimProposal, educationClaimDefId, educationRevRegId)

        // Verify claims
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(personSchemaId, personClaimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"),
                VerifyClaimFlow.ProofAttribute(educationSchemaId, educationClaimDefId, issuer.getPartyDid(), schemaEducation.schemaAttr1, "University")
        )

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(personSchemaId, personClaimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaPersonAttrInt.toInt() - 10),
                VerifyClaimFlow.ProofPredicate(educationSchemaId, educationClaimDefId, issuer.getPartyDid(), schemaEducation.schemaAttr2, schemaEducationAttrInt.toInt() - 10))

        val claimVerified = verifyClaim(bob, alice, attributes, predicates, Interval.allTime())
        assertTrue(claimVerified)
    }

    @Test
    fun `1 claim 1 prover without predicates setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val (claimDefId, revRegId) = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, claimDefId, revRegId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith")
        )

        val claimVerified = verifyClaim(bob, alice, attributes, emptyList(), Interval.allTime())
        assertTrue(claimVerified)
    }

    @Test
    fun `1 claim 1 prover not all attributes to verify setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val (claimDefId, revRegId) = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, claimDefId, revRegId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"),
                VerifyClaimFlow.ProofAttribute(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, "")
        )

        val claimVerified = verifyClaim(bob, alice, attributes, emptyList(), Interval.allTime())
        assertTrue(claimVerified)
    }
}