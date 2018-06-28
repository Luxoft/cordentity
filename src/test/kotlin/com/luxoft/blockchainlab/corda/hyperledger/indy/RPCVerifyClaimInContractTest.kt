package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateClaimDefFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateSchemaFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.IssueClaimFlow
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.flows.FlowException
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort.Companion.parse
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@Ignore("TODO: migrate or delete")
class RPCVerifyClaimInContractTest {


    val schemaOwnerDid = "V4SGRU86Z58d6TV7PBUe6f"



    companion object {

        private lateinit var issuer: CordaRPCOps
        private lateinit var prover: CordaRPCOps
        private lateinit var verifier: CordaRPCOps

        @JvmStatic
        @BeforeClass
        fun init() {
            val issuerAddress = parse("localhost:10002")
            val issuerClient = CordaRPCClient(issuerAddress)
            issuer = issuerClient.start("user1", "test").proxy


            val proverAddress = parse("localhost:10102")
            val proverClient = CordaRPCClient(proverAddress)
            prover = proverClient.start("user1", "test").proxy


            val verifierAddress = parse("localhost:10202")
            val verifierClient = CordaRPCClient(verifierAddress)
            verifier = verifierClient.start("user1", "test").proxy
        }

    }


    private fun issueSchemaAndClaimDef(schemaOwner: CordaRPCOps,
                                       claimDefOwner: CordaRPCOps,
                                       schema: Schema) {

        schemaOwner.startFlowDynamic(
                CreateSchemaFlow.Authority::class.java,
                schema.getSchemaName(),
                schema.getSchemaVersion(),
                schema.getSchemaAttrs()
        ).returnValue.get(30, TimeUnit.SECONDS)


        claimDefOwner.startFlowDynamic(
                CreateClaimDefFlow.Authority::class.java,
                schemaOwnerDid,
                schema.getSchemaName(),
                schema.getSchemaVersion()
        ).returnValue.get(30, TimeUnit.SECONDS)
    }

    private fun issueClaim(claimProver: CordaRPCOps,
                           claimIssuer: CordaRPCOps,
                           claimProposal: String,
                           schema: Schema) {

        val claim = claimProver.startFlowDynamic (
                IssueClaimFlow.Prover::class.java,
                        schemaOwnerDid,
                        schema.getSchemaName(),
                        schema.getSchemaVersion(),
                        claimProposal,
                        "master",
                        claimIssuer.nodeInfo().legalIdentities.first().name.organisation).returnValue.get(30, TimeUnit.SECONDS)
    }
}