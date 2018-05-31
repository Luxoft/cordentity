package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.schema.Schema
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.schema.SchemaEducation
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.schema.SchemaPerson
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
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue


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
            it.registerInitiatedFlow(IssueClaimFlow.Issuer::class.java)
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
                                       schema: Schema) {

        val schemaOwnerDid = schemaOwner.services.cordaService(IndyService::class.java).indyUser.did
        val schemaFuture = schemaOwner.services.startFlow(
                CreateSchemaFlow.Authority(
                        schema.getSchemaName(),
                        schema.getSchemaVersion(),
                        schema.getSchemaAttrs())).resultFuture

        net.runNetwork()
        schemaFuture.getOrThrow(Duration.ofSeconds(30))

        val claimDefFuture = claimDefOwner.services.startFlow(
                CreateClaimDefFlow.Authority(
                        schemaOwnerDid,
                        schema.getSchemaName(),
                        schema.getSchemaVersion())).resultFuture

        net.runNetwork()
        claimDefFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueClaim(claimProver: StartedNode<MockNode>,
                           claimIssuer: StartedNode<MockNode>,
                           schemaOwner: StartedNode<MockNode>,
                           claimProposal: String,
                           schema: Schema) {

        val schemaOwnerDid = schemaOwner.services.cordaService(IndyService::class.java).indyUser.did
        val claimIssuer = claimIssuer.info.singleIdentity().name

        val schemaDetails = IndyUser.SchemaDetails(schema.getSchemaName(), schema.getSchemaVersion(), schemaOwnerDid)
        val claimFuture = claimProver.services.startFlow(
                IssueClaimFlow.Prover(
                        schemaDetails,
                        claimProposal,
                        // TODO: Master Secret should be used from the outside
                        schemaOwner.services.cordaService(IndyService::class.java).indyUser.masterSecret,
                        claimIssuer)
        ).resultFuture

        net.runNetwork()
        claimFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun verifyClaim(verifier: StartedNode<MockNode>,
                            prover: StartedNode<MockNode>,
                            attributes: List<IndyUser.ProofAttribute>,
                            predicates: List<IndyUser.ProofPredicate>,
                            assertion: (actual: Boolean) -> Unit) {

        val proofCheckResultFuture = verifier.services.startFlow(
                VerifyClaimFlow.Verifier(
                        attributes,
                        predicates,
                        prover.info.singleIdentity().name)).resultFuture

        net.runNetwork()
        val proofCheckResult = proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30))
        assertion(proofCheckResult)
    }

    private fun multipleClaimsByDiffIssuers(schema1AttrInt: String,
                                            schema2AttrInt: String,
                                            valueToProofSchema1AttrInt: String,
                                            valueToProofSchema2AttrInt: String,
                                            assertion: (actual: Boolean) -> Unit) {

        // Request permissions from trustee to write on ledger
        setPermissions(bob, issuer)

        // Issue schemas and claimDefs
        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        issueSchemaAndClaimDef(issuer, bob, schemaEducation)

        // Issue claim #1
        var claimProposal = String.format(schemaPerson.getSchemaProposal(),
                "John Smith", "119191919", schema1AttrInt, schema1AttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson)

        // Issue claim #2
        claimProposal = String.format(schemaEducation.getSchemaProposal(),
                "University", "119191918", schema2AttrInt, schema2AttrInt)

        issueClaim(alice, bob, issuer, claimProposal, schemaEducation)

        // Verify claims
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaPersonDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)
        val schemaEducationDetails = IndyUser.SchemaDetails(schemaEducation.getSchemaName(), schemaEducation.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaPersonDetails, schemaPerson.schemaAttr1),
                IndyUser.ProofAttribute(schemaEducationDetails, schemaPerson.schemaAttr1)
        )

        val predicates = listOf(
                IndyUser.ProofPredicate(schemaPersonDetails, schemaPerson.schemaAttr2, valueToProofSchema1AttrInt.toInt()),
                IndyUser.ProofPredicate(schemaEducationDetails, schemaEducation.schemaAttr2, valueToProofSchema2AttrInt.toInt()))

        verifyClaim(bob, alice, attributes, predicates, assertion)
    }

    @Test
    fun validMultipleClaimsByDiffIssuers() {

        val schema1AttrInt = "1988"
        val valueToCheckSchema1AttrInt = "1978"
        val schema2AttrInt = "2016"
        val valueToCheckSchema2AttrInt = "2006"

        multipleClaimsByDiffIssuers(
                schema1AttrInt,
                schema2AttrInt,
                valueToCheckSchema1AttrInt,
                valueToCheckSchema2AttrInt,
                { res -> assertTrue(res) })
    }

    @Test
    fun invalidMultipleClaimsByDiffIssuers() {

        val schema1AttrInt = "1988"
        val valueToCheckSchema1AttrInt = "1978"
        val schema2AttrInt = "2016"
        val valueToCheckSchema2AttrInt = "2026"

        multipleClaimsByDiffIssuers(
                schema1AttrInt,
                schema2AttrInt,
                valueToCheckSchema1AttrInt,
                valueToCheckSchema2AttrInt,
                { res -> assertFalse(res) })
    }

    @Test
    fun singleClaimLifecycle() {

        val schemaPerson = SchemaPerson()

        // Verify ClaimSchema & Defs
        issueSchemaAndClaimDef(issuer, issuer, schemaPerson)

        // Issue claim
        val schemaAttrInt = "1988"
        val claimProposal = String.format(schemaPerson.getSchemaProposal(),
                "John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson)

        // Verify claim
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaDetails, schemaPerson.schemaAttr1))

        val predicates = listOf(
                // -10 to check >=
                IndyUser.ProofPredicate(schemaDetails, schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10))

        verifyClaim(bob, alice, attributes, predicates, { res -> assertTrue(res) })
    }

    @Test
    fun multipleClaimsBySameIssuer() {

        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        issueSchemaAndClaimDef(issuer, issuer, schemaPerson)
        issueSchemaAndClaimDef(issuer, issuer, schemaEducation)

        // Issue claim #1
        val schemaPersonAttrInt = "1988"
        var claimProposal = String.format(schemaPerson.getSchemaProposal(), "John Smith", "119191919",
                schemaPersonAttrInt, schemaPersonAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaPerson)

        // Issue claim #2
        val schemaEducationAttrInt = "2016"
        claimProposal = String.format(schemaEducation.getSchemaProposal(), "University", "119191918",
                schemaEducationAttrInt, schemaEducationAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schemaEducation)

        // Verify claims
        val schemaOwner = issuer.services.cordaService(IndyService::class.java).indyUser.did
        val schemaPersonDetails = IndyUser.SchemaDetails(schemaPerson.getSchemaName(), schemaPerson.getSchemaVersion(), schemaOwner)
        val schemaEducationDetails = IndyUser.SchemaDetails(schemaEducation.getSchemaName(), schemaEducation.getSchemaVersion(), schemaOwner)

        val attributes = listOf(
                IndyUser.ProofAttribute(schemaPersonDetails, schemaPerson.schemaAttr1),
                IndyUser.ProofAttribute(schemaEducationDetails,  schemaPerson.schemaAttr1)
        )

        val predicates = listOf(
                // -10 to check >=
                IndyUser.ProofPredicate(schemaPersonDetails, schemaPerson.schemaAttr2, schemaPersonAttrInt.toInt() - 10),
                IndyUser.ProofPredicate(schemaEducationDetails, schemaEducation.schemaAttr2, schemaEducationAttrInt.toInt() - 10))

        verifyClaim(bob, alice, attributes, predicates, { res -> assertTrue(res) })
    }
}