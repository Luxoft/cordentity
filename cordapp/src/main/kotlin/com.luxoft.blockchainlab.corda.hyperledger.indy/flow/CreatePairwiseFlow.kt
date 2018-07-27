package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap

object CreatePairwiseFlow {

    @InitiatingFlow
    open class Prover(private val authority: CordaX500Name) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                val otherSide: Party = whoIs(authority)
                val flowSession: FlowSession = initiateFlow(otherSide)

                val sessionDid = flowSession.receive<String>().unwrap{ theirIdentityRecord ->
                    indyUser().createSessionDid(IndyUser.IdentityDetails(theirIdentityRecord))
                }

                flowSession.send(indyUser().getIdentity(sessionDid).getIdentityRecord())
                return sessionDid

            } catch (ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }


    @InitiatedBy(CreatePairwiseFlow.Prover::class)
    open class Issuer (private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {

            try {
                val myIdentityRecord = indyUser().getIdentity().getIdentityRecord()

                flowSession.sendAndReceive<String>(myIdentityRecord).unwrap { theirIdentityRecord ->
                    indyUser().addKnownIdentities(IndyUser.IdentityDetails(theirIdentityRecord))
                }
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }

    }
}