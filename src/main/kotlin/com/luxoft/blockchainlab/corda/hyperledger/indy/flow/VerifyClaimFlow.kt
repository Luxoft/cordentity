package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimProof
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimChecker
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.model.Proof
import com.luxoft.blockchainlab.hyperledger.indy.model.ProofReq
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

object VerifyClaimFlow {

    @InitiatingFlow
    @StartableByRPC
    open class Verifier (
            private val identifier: String,
            private val attributes: List<IndyUser.ProofAttribute>,
            private val predicates: List<IndyUser.ProofPredicate>,
            private val proverName: CordaX500Name
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean  {
            try {
                val prover: Party = whoIs(proverName)
                val flowSession: FlowSession = initiateFlow(prover)

                val proofRequest = indyUser().createProofReq(attributes, predicates)

                val verifyClaimOut = flowSession.sendAndReceive<Proof>(proofRequest).unwrap { proof ->
                    val claimProofOut = IndyClaimProof(identifier, proofRequest, proof, listOf(ourIdentity, prover))
                    StateAndContract(claimProofOut, ClaimChecker::class.java.name)
                }

                val expectedAttrs = attributes
                        .filter { it.value.isEmpty() }
                        .associateBy({ it.field }, { it.value })
                        .map { ClaimChecker.ExpectedAttr(it.key, it.value) }

                val verifyClaimData = ClaimChecker.Commands.Verify(expectedAttrs)
                val verifyClaimSigners = listOf(ourIdentity.owningKey, prover.owningKey)

                val verifyClaimCmd = Command(verifyClaimData, verifyClaimSigners)

                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(verifyClaimOut, verifyClaimCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(signedTrx))

                return true

            } catch (e: Exception) {
                logger.error("", e)
                return false
            }
        }
    }

    @InitiatedBy(VerifyClaimFlow.Verifier::class)
    open class Prover (private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                flowSession.receive(ProofReq::class.java).unwrap { indyProofReq ->
                    // TODO: Master Secret should be received from the outside
                    val masterSecret = indyUser().masterSecret
                    flowSession.send(indyUser().createProof(indyProofReq, masterSecret))
                }

                val flow = object : SignTransactionFlow(flowSession) {
                    // TODO: Add some checks here.
                    override fun checkTransaction(stx: SignedTransaction) = Unit
                }

                subFlow(flow)

            } catch (e: Exception) {
                logger.error("", e)
                throw FlowException(e.message)
            }
        }
    }
}