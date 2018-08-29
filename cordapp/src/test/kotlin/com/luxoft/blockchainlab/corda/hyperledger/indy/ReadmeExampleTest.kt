package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.natpryce.konfig.Configuration
import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.TestConfigurationsProvider
import net.corda.core.identity.CordaX500Name
import net.corda.node.internal.StartedNode
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.util.*
import kotlin.math.absoluteValue


class ReadmeExampleTest {

    private lateinit var net: InternalMockNetwork
    private lateinit var notary: StartedNode<InternalMockNetwork.MockNode>
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
                networkParameters = testNetworkParameters(maxTransactionSize = 10485760 * 5))

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
            it.registerInitiatedFlow(VerifyClaimFlow.Prover::class.java)
        }
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
                        "indyuser.walletName" to name + RD.nextLong().absoluteValue
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
        } finally {
            net.stopNodes()
        }
    }

    @Test
    fun `grocery store example`() {
        val ministry: StartedNode<*> = issuer
        val alice: StartedNode<*> = alice
        val store: StartedNode<*> = bob

        // Each Corda node has a X500 name:
        val ministryX500 = ministry.info.singleIdentity().name
        val aliceX500 = alice.info.singleIdentity().name

        // And each Indy node has a DID, a.k.a Decentralized ID:
        val ministryDID = store.services.startFlow(
                GetDidFlow.Initiator(ministryX500)
        ).resultFuture.get()

        // To allow customers and shops to communicate, Ministry issues a shopping scheme:
        val schemaId = ministry.services.startFlow(
                CreateSchemaFlow.Authority(
                        "shopping scheme",
                        "1.0",
                        listOf("NAME", "BORN"))).resultFuture.get()

        // Ministry creates a claim definition for the shopping scheme:
        val (credDefId, revRegId) = ministry.services.startFlow(
                CreateClaimDefinitionFlow.Authority(schemaId)
        ).resultFuture.get()

        // Ministry verifies Alice's legal status and issues her a shopping credential:
        val credentialProposal = """
        {
        "NAME":{"raw":"Alice", "encoded":"119191919"},
        "BORN":{"raw":"2000",  "encoded":"2000"}
        }
        """

        ministry.services.startFlow(
                IssueClaimFlow.Issuer(
                        UUID.randomUUID().toString(),
                        credDefId,
                        credentialProposal,
                        revRegId,
                        aliceX500
                )
        ).resultFuture.get()

        // When Alice comes to grocery store, the store asks Alice to verify that she is legally allowed to buy drinks:

        // Alice.BORN >= currentYear - 18
        val eighteenYearsAgo = LocalDateTime.now().minusYears(18).year
        val legalAgePredicate = VerifyClaimFlow.ProofPredicate(schemaId, credDefId, ministryDID, "BORN", eighteenYearsAgo)

        val verified = store.services.startFlow(
                VerifyClaimFlow.Verifier(
                        UUID.randomUUID().toString(),
                        emptyList(),
                        listOf(legalAgePredicate),
                        aliceX500
                )
        ).resultFuture.get()

        // If the verification succeeds, the store can be sure that Alice's age is above 18.
        println("You can buy drinks: $verified")
    }

}