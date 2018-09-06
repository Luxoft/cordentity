package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.AssignPermissionsFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreatePairwiseFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.IssueClaimFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.VerifyClaimFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.natpryce.konfig.Configuration
import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.TestConfigurationsProvider
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.FlowStateMachine
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.node.services.api.StartedNodeServices
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import net.corda.testing.node.internal.newContext
import org.junit.After
import org.junit.Before
import java.util.*
import kotlin.math.absoluteValue

open class IndyCordaSetup {

    protected lateinit var net: InternalMockNetwork
        private set

    private val parties: MutableList<StartedNode<MockNode>> = mutableListOf()

    protected val random = Random()

    fun createPartyNode(legalName: CordaX500Name? = null): StartedNode<MockNode> {
        val party = net.createPartyNode(legalName)

        parties.add(party)

        with(party) {
            registerInitiatedFlow(AssignPermissionsFlow.Authority::class.java)
            registerInitiatedFlow(CreatePairwiseFlow.Issuer::class.java)
            registerInitiatedFlow(IssueClaimFlow.Prover::class.java)
            registerInitiatedFlow(VerifyClaimFlow.Prover::class.java)
            registerInitiatedFlow(VerifyClaimFlow.Prover::class.java)
        }

        return party
    }

    @Before
    fun commonSetup() {
        TestConfigurationsProvider.provider = object : TestConfigurationsProvider {
            override fun getConfig(name: String): Configuration? {
                // Watch carefully for these hard-coded values
                // Now we assume that issuer(indy trustee) is the first created node from SomeNodes
                return if (name == "Issuer") {
                    ConfigurationMap(mapOf(
                            "indyuser.walletName" to name,
                            "indyuser.role" to "trustee",
                            "indyuser.did" to "V4SGRU86Z58d6TV7PBUe6f",
                            "indyuser.seed" to "000000000000000000000000Trustee1",
                            "indyuser.genesisFile" to PoolManager.DEFAULT_GENESIS_FILE
                    ))
                } else ConfigurationMap(mapOf(
                        "indyuser.walletName" to name + random.nextLong().absoluteValue,
                        "indyuser.genesisFile" to PoolManager.DEFAULT_GENESIS_FILE
                ))
            }
        }

        net = InternalMockNetwork(
                cordappPackages = listOf("com.luxoft.blockchainlab.corda.hyperledger.indy"),
                networkParameters = testNetworkParameters(maxTransactionSize = 10485760 * 5))

        parties.clear()
    }

    @After
    fun commonTearDown() {
        try {
            parties.forEach {
                it.services.cordaService(IndyService::class.java).indyUser.close()
            }
        } finally {
            net.stopNodes()
        }
    }

    protected fun <T> StartedNodeServices.startFlow(logic: FlowLogic<T>): FlowStateMachine<T> {
        val machine = startFlow(logic, newContext()).getOrThrow()

        return object : FlowStateMachine<T> by machine {
            override val resultFuture: CordaFuture<T>
                get() {
                    net.runNetwork()
                    return machine.resultFuture
                }
        }
    }
}