package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.CredentialChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredential
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.model.CredentialOffer
import com.luxoft.blockchainlab.hyperledger.indy.model.CredentialRequest
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

object IssueCredentialFlow {

    @InitiatingFlow
    @StartableByRPC
    open class Issuer(private val identifier: String,
                      private val credDefId: String,
                      private val proposal: String,
                      private val proverName: CordaX500Name) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val prover: Party = whoIs(proverName)
            val flowSession: FlowSession = initiateFlow(prover)

            try {
                val offer = flowSession.receive<String>().unwrap { sessionalDid ->
                    indyUser().createCredOffer(credDefId)
                }

                val newClaimOut = flowSession.sendAndReceive<CredentialRequest>(offer).unwrap { credReq ->
                    verifyClaimAttributeValues(credReq)
                    val cred = indyUser().issueCred(credReq, proposal, offer)
                    val credOut = IndyCredential(identifier, credReq, cred, indyUser().did, listOf(ourIdentity, prover))
                    StateAndContract(credOut, CredentialChecker::class.java.name)
                }

                val newClaimData = CredentialChecker.Commands.Issue()
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

                val sessionDid = subFlow(CreatePairwiseFlow.Prover(issuer))

                val offer = flowSession.sendAndReceive<CredentialOffer>(sessionDid).unwrap { offer -> offer }

                val issuerDid = subFlow(GetDidFlow.Initiator(issuer))

                val credReq = indyUser().createCredReq(issuerDid, sessionDid, offer)
                flowSession.send(credReq)

                val flow = object : SignTransactionFlow(flowSession) {
                    override fun checkTransaction(stx: SignedTransaction) {
                        val output = stx.tx.toLedgerTransaction(serviceHub).outputs.singleOrNull()
                        val state = output!!.data
                        when(state) {
                            is IndyCredential -> {
                                require(state.credReq == credReq) { "Received incorrected CredentialRequest"}
                                indyUser().receiveCred(state.cred, state.credReq, offer)
                            }
                            else -> throw FlowException("invalid output state. IndyCredential is expected")
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