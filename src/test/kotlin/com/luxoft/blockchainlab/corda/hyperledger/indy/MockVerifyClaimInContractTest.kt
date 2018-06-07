package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.flow.VerifyClaimInContractDemoFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.schema.Schema
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.schema.SchemaHappiness
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.natpryce.konfig.Configuration
import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.TestConfigurationsProvider
import net.corda.core.flows.FlowException
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.startFlow
import org.junit.*
import java.time.Duration
import kotlin.test.fail


class MockVerifyClaimInContractTest {

    private lateinit var net: InternalMockNetwork
    private lateinit var notary: StartedNode<InternalMockNetwork.MockNode>
    private lateinit var issuer: StartedNode<InternalMockNetwork.MockNode>
    private lateinit var alice: StartedNode<InternalMockNetwork.MockNode>
    private lateinit var bob: StartedNode<InternalMockNetwork.MockNode>

    private lateinit var parties: List<StartedNode<InternalMockNetwork.MockNode>>

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

    private fun issueSchemaAndClaimDef(schemaOwner: StartedNode<InternalMockNetwork.MockNode>,
                                       claimDefOwner: StartedNode<InternalMockNetwork.MockNode>,
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

    private fun issueClaim(claimProver: StartedNode<InternalMockNetwork.MockNode>,
                           claimIssuer: StartedNode<InternalMockNetwork.MockNode>,
                           schemaOwner: StartedNode<InternalMockNetwork.MockNode>,
                           claimProposal: String,
                           schema: Schema) {

        val schemaOwnerDid = schemaOwner.services.cordaService(IndyService::class.java).indyUser.did

        val schemaDetails = IndyUser.SchemaDetails(
                schema.getSchemaName(),
                schema.getSchemaVersion(),
                schemaOwnerDid)

        val claimFuture = claimIssuer.services.startFlow(
                IssueClaimFlow.Issuer(
                        schemaDetails,
                        claimProposal,
                        claimProver.info.singleIdentity().name)
        ).resultFuture

        net.runNetwork()
        claimFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun verifyClaim(verifier: StartedNode<InternalMockNetwork.MockNode>,
                             prover: StartedNode<InternalMockNetwork.MockNode>) {

        val proofCheckResultFuture = verifier.services.startFlow(
                VerifyClaimInContractDemoFlow.Verifier(
                        prover.info.legalIdentities.first().name.organisation,
                        issuer.services.cordaService(IndyService::class.java).indyUser.did
                )
        ).resultFuture

        net.runNetwork()
        proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30))
    }


    @Test
    fun validClaim() {

        val schema = SchemaHappiness()

        // Verify ClaimSchema & Defs
        issueSchemaAndClaimDef(issuer, issuer, schema)

        // Issue claim
        val schemaAttrInt = "22"
        val claimProposal = String.format(schema.getSchemaProposal(),
                "yes", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schema)

        // Verify claim
        verifyClaim(bob, alice)
    }


    @Test
    fun invalidClaim() {

        val schema = SchemaHappiness()

        // Verify ClaimSchema & Defs
        issueSchemaAndClaimDef(issuer, issuer, schema)

        // Issue claim
        val schemaAttrInt = "20"
        val claimProposal = String.format(schema.getSchemaProposal(),
                "yes", "119191919", schemaAttrInt, schemaAttrInt)

        issueClaim(alice, issuer, issuer, claimProposal, schema)

        // Verify claim
        try {
            verifyClaim(bob, alice)
            fail("Verification should fail")
        } catch (e: FlowException) {
            // Expected exception
        }  catch (e: Exception) {
            // Unexpected exception
            e.printStackTrace()
            fail(e.message)
        }
    }

}