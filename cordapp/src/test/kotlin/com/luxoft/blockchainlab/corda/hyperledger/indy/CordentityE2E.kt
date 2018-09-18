package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.hyperledger.indy.Interval
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import org.junit.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.Duration
import java.util.*


class CordentityE2E : CordaTestBase() {

    private lateinit var trustee: StartedNode<MockNode>
    private lateinit var notary: StartedNode<MockNode>
    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>

    @Before
    fun setup() {
        notary = net.defaultNotaryNode

        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
        issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = createPartyNode(CordaX500Name("Bob", "London", "GB"))

        // Request permissions from trustee to write on ledger
        setPermissions(issuer, trustee)
        setPermissions(bob, trustee)
    }

    private fun issueSchema(schemaOwner: StartedNode<MockNode>, schema: Schema): String {
        val schemaFuture = schemaOwner.services.startFlow(
                CreateSchemaFlow.Authority(schema.schemaName, schema.schemaVersion, schema.schemaAttrs)
        ).resultFuture

        return schemaFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueClaimDefinition(claimDefOwner: StartedNode<MockNode>, schemaId: String): String {
        val claimDefFuture = claimDefOwner.services.startFlow(
                CreateClaimDefinitionFlow.Authority(schemaId)
        ).resultFuture

        return claimDefFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueClaim(
            claimProver: StartedNode<MockNode>,
            claimIssuer: StartedNode<MockNode>,
            claimProposal: String,
            claimDefId: String
    ): String {

        val identifier = UUID.randomUUID().toString()

        val claimFuture = claimIssuer.services.startFlow(
                IssueClaimFlow.Issuer(
                        identifier,
                        claimProposal,
                        claimDefId,
                        claimProver.getName()
                )
        ).resultFuture

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

        return proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun multipleClaimsByDiffIssuers(attrs: Map<String, String>, preds: Map<String, String>): Boolean {

        val (attr1, attr2) = attrs.entries.toList()
        val (pred1, pred2) = preds.entries.toList()

        // Issue schemas and claimDefs
        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val personSchemaId = issueSchema(issuer, schemaPerson)
        val educationSchemaId = issueSchema(bob, schemaEducation)

        val personClaimDefId = issueClaimDefinition(issuer, personSchemaId)
        val educationClaimDefId = issueClaimDefinition(bob, educationSchemaId)

        // Issue claim #1
        var claimProposal = schemaPerson.formatProposal(attr1.key, "119191919", pred1.key, pred1.key)

        issueClaim(alice, issuer, claimProposal, personClaimDefId)

        // Issue claim #2
        claimProposal = schemaEducation.formatProposal(attr2.key, "119191918", pred2.key, pred2.key)

        issueClaim(alice, bob, claimProposal, educationClaimDefId)

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
        val claimDefId = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, claimDefId)

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
        val claimDefId = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        val claimId = issueClaim(alice, issuer, claimProposal, claimDefId)

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

        val personClaimDefId = issueClaimDefinition(issuer, personSchemaId)
        val educationClaimDefId = issueClaimDefinition(issuer, educationSchemaId)

        // Issue claim #1
        val schemaPersonAttrInt = "1988"
        var claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaPersonAttrInt, schemaPersonAttrInt)

        issueClaim(alice, issuer, claimProposal, personClaimDefId)

        // Issue claim #2
        val schemaEducationAttrInt = "2016"
        claimProposal = schemaEducation.formatProposal("University", "119191918", schemaEducationAttrInt, schemaEducationAttrInt)

        issueClaim(alice, issuer, claimProposal, educationClaimDefId)

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
        val claimDefId = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, claimDefId)

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
        val claimDefId = issueClaimDefinition(issuer, schemaId)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, claimDefId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"),
                VerifyClaimFlow.ProofAttribute(schemaId, claimDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, "")
        )

        val claimVerified = verifyClaim(bob, alice, attributes, emptyList(), Interval.allTime())
        assertTrue(claimVerified)
    }
}