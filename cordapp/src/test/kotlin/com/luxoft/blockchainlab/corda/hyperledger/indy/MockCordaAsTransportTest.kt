package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
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


class MockCordaAsTransportTest : CordaTestBase() {

    private lateinit var notary: StartedNode<MockNode>
    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>

    @Before
    fun setup() {
        notary = net.defaultNotaryNode

        issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = createPartyNode(CordaX500Name("Bob", "London", "GB"))
    }

    private fun setPermissions(issuer: StartedNode<MockNode>,
                               authority: StartedNode<MockNode>) {
        val permissionsFuture = issuer.services.startFlow(AssignPermissionsFlow.Issuer(
                authority = authority.info.singleIdentity().name, role = "TRUSTEE")).resultFuture

        permissionsFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueSchemaAndClaimDef(schemaOwner: StartedNode<MockNode>,
                                       claimDefOwner: StartedNode<MockNode>,
                                       schema: Schema): Pair<String, String> {

        // create schema
        val schemaFuture = schemaOwner.services.startFlow(
                CreateSchemaFlow.Authority(
                        schema.schemaName,
                        schema.schemaVersion,
                        schema.schemaAttrs)).resultFuture

        val schemaId = schemaFuture.getOrThrow(Duration.ofSeconds(30))

        // create credential definition
        val claimDefFuture = claimDefOwner.services.startFlow(
                CreateClaimDefFlow.Authority(schemaId)).resultFuture

        val credDefId = claimDefFuture.getOrThrow(Duration.ofSeconds(30))

        return Pair(schemaId, credDefId)
    }

    private fun issueClaim(claimProver: StartedNode<MockNode>,
                           claimIssuer: StartedNode<MockNode>,
                           claimProposal: String,
                           claimDefId: String) {
        val identifier = UUID.randomUUID().toString()

        val claimFuture = claimIssuer.services.startFlow(
                IssueClaimFlow.Issuer(
                        identifier,
                        claimDefId,
                        claimProposal,
                        claimProver.getName())).resultFuture

        claimFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun verifyClaim(verifier: StartedNode<MockNode>,
                            prover: StartedNode<MockNode>,
                            attributes: List<VerifyClaimFlow.ProofAttribute>,
                            predicates: List<VerifyClaimFlow.ProofPredicate>): Boolean {
        val identifier = UUID.randomUUID().toString()

        val proofCheckResultFuture = verifier.services.startFlow(
                VerifyClaimFlow.Verifier(
                        identifier,
                        attributes,
                        predicates,
                        prover.getName())).resultFuture

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

        val (personSchemaId, personCredDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        val (educationSchemaId, educationCredDefId) = issueSchemaAndClaimDef(issuer, bob, schemaEducation)

        // Issue claim #1
        var claimProposal = schemaPerson.formatProposal(attr1.key, "119191919", pred1.key, pred1.key)

        issueClaim(alice, issuer, claimProposal, personCredDefId)

        // Issue claim #2
        claimProposal = schemaEducation.formatProposal(attr2.key, "119191918", pred2.key, pred2.key)

        issueClaim(alice, bob, claimProposal, educationCredDefId)

        // Verify claims
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, attr1.value),
                VerifyClaimFlow.ProofAttribute(educationSchemaId, educationCredDefId, bob.getPartyDid(), schemaEducation.schemaAttr1, attr2.value)
        )

        val predicates = listOf(
                VerifyClaimFlow.ProofPredicate(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, pred1.value.toInt()),
                VerifyClaimFlow.ProofPredicate(educationSchemaId, educationCredDefId, bob.getPartyDid(), schemaEducation.schemaAttr2, pred2.value.toInt())
        )

        return verifyClaim(bob, alice, attributes, predicates)
    }

    @Test
    fun validMultipleClaimsByDiffIssuers() {
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
    fun `invalid predicates of multiple claims issued by diff authorities`() {
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
    fun `invalid attributes of multiple claims issued by diff authorities`() {
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
    fun `single claim full lifecycle`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val (personSchemaId, personCredDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, personCredDefId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"))

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10))

        val claimVerified = verifyClaim(bob, alice, attributes, predicates)
        assertTrue(claimVerified)
    }

    @Test
    fun multipleClaimsBySameIssuer() {

        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val (personSchemaId, personCredDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        val (educationSchemaId, educationCredDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaEducation)

        // Issue claim #1
        val schemaPersonAttrInt = "1988"
        var claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaPersonAttrInt, schemaPersonAttrInt)

        issueClaim(alice, issuer, claimProposal, personCredDefId)

        // Issue claim #2
        val schemaEducationAttrInt = "2016"
        claimProposal = schemaEducation.formatProposal("University", "119191918", schemaEducationAttrInt, schemaEducationAttrInt)

        issueClaim(alice, issuer, claimProposal, educationCredDefId)

        // Verify claims
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"),
                VerifyClaimFlow.ProofAttribute(educationSchemaId, educationCredDefId, issuer.getPartyDid(), schemaEducation.schemaAttr1, "University")
        )

        val predicates = listOf(
                // -10 to check >=
                VerifyClaimFlow.ProofPredicate(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, schemaPersonAttrInt.toInt() - 10),
                VerifyClaimFlow.ProofPredicate(educationSchemaId, educationCredDefId, issuer.getPartyDid(), schemaEducation.schemaAttr2, schemaEducationAttrInt.toInt() - 10))

        val claimVerified = verifyClaim(bob, alice, attributes, predicates)
        assertTrue(claimVerified)
    }

    @Test
    fun `empty Predicates on verification for single issued claim`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val (personSchemaId, personCredDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, personCredDefId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith")
        )

        val claimVerified = verifyClaim(bob, alice, attributes, emptyList())
        assertTrue(claimVerified)
    }

    @Test
    fun `not all attributes to verify`() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        val (personSchemaId, personCredDefId) = issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, claimProposal, personCredDefId)

        // Verify claim
        val attributes = listOf(
                VerifyClaimFlow.ProofAttribute(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr1, "John Smith"),
                VerifyClaimFlow.ProofAttribute(personSchemaId, personCredDefId, issuer.getPartyDid(), schemaPerson.schemaAttr2, "")
        )

        val claimVerified = verifyClaim(bob, alice, attributes, emptyList())
        assertTrue(claimVerified)
    }
}