package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import com.luxoft.blockchainlab.hyperledger.indy.model.ClaimOffer
import com.luxoft.blockchainlab.hyperledger.indy.model.ClaimReq
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

/**
 * A flow to issue an Indy credential based on proposal [credProposal]
 * */
object IssueClaimFlow {

    @InitiatingFlow
    @StartableByRPC
    open class Issuer(private val identifier: String,
                      private val credDefId: String,
                      private val credProposal: String,
                      private val proverName: CordaX500Name) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val prover: Party = whoIs(proverName)
            val flowSession: FlowSession = initiateFlow(prover)

            try {
                val offer = indyUser().createClaimOffer(credDefId)

                val newClaimOut = flowSession.sendAndReceive<ClaimReq>(offer).unwrap { claimReq ->
                    verifyClaimAttributeValues(claimReq)
                    val claim = indyUser().issueClaim(claimReq, credProposal, offer)
                    val claimOut = IndyClaim(identifier, claimReq, claim, indyUser().did, listOf(ourIdentity, prover))
                    StateAndContract(claimOut, ClaimChecker::class.java.name)
                }

                val newClaimData = ClaimChecker.Commands.Issue()
                val newClaimSigners = listOf(ourIdentity.owningKey, prover.owningKey)

                val newClaimCmd = Command(newClaimData, newClaimSigners)

                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(newClaimOut, newClaimCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(signedTrx))

            } catch(ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }

    @InitiatedBy(Issuer::class)
    open class Prover (private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val issuer = flowSession.counterparty.name

                val offer = flowSession.receive<ClaimOffer>().unwrap { offer -> offer }
                val sessionDid = subFlow(CreatePairwiseFlow.Prover(issuer))
                val issuerDid = subFlow(GetDidFlow.Initiator(issuer))

                val claimReq = indyUser().createClaimReq(issuerDid, sessionDid, offer)
                flowSession.send(claimReq)

                val flow = object : SignTransactionFlow(flowSession) {
                    override fun checkTransaction(stx: SignedTransaction) {
                        val output = stx.tx.toLedgerTransaction(serviceHub).outputs.singleOrNull()
                        val state = output!!.data
                        when(state) {
                            is IndyClaim -> {
                                require(state.claimReq == claimReq) { "Received incorrected ClaimReq"}
                                indyUser().receiveClaim(state.claim, state.claimReq, offer)
                            }
                            else -> throw FlowException("invalid output state. IndyClaim is expected")
                        }
                    }
                }

                subFlow(flow)

            } catch (ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }
}