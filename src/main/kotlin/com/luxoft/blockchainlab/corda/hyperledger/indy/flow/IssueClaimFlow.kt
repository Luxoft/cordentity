package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.model.ClaimOffer
import com.luxoft.blockchainlab.hyperledger.indy.model.ClaimReq
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

object IssueClaimFlow {

    @InitiatingFlow
    @StartableByRPC
    open class Issuer(private val identifier: String,
                      private val schema: IndyUser.SchemaDetails,
                      private val proposal: String,
                      private val proverName: CordaX500Name) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val prover: Party = whoIs(proverName)
            val flowSession: FlowSession = initiateFlow(prover)

            try {
                logger.error("-----------------LOG---------------")
                val offer = flowSession.receive<String>().unwrap { sessionalDid ->
                    indyUser().createClaimOffer(sessionalDid, schema)
                }

                val newClaimOut = flowSession.sendAndReceive<ClaimReq>(offer).unwrap { claimReq ->
                    verifyClaimAttributeValues(claimReq)
                    logger.error("-----------------LOG-CLAIM-ISSUE---------------")
                    val claim = indyUser().issueClaim(claimReq, proposal)
                    val claimOut = IndyClaim(identifier, claimReq, claim, listOf(ourIdentity, prover))
                    StateAndContract(claimOut, ClaimChecker::class.java.name)
                }

                val newClaimData = ClaimChecker.Commands.Issue()
                val newClaimSigners = listOf(ourIdentity.owningKey, prover.owningKey)

                val newClaimCmd = Command(newClaimData, newClaimSigners)

                logger.error("-----------------LOG-TRANSACTION-CREATE----------------")
                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(newClaimOut, newClaimCmd)

                logger.error("-----------------LOG-TRANSACTION-VERIFICATION----------------")
                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                logger.error("-----------------LOG-SIGNING----------------")
                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                logger.error("-----------------LOG-FIN----------------")
                subFlow(FinalityFlow(signedTrx))

                logger.error("-----------------LOG-BYE-BYE----------------")

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

                val schema = flowSession.sendAndReceive<ClaimOffer>(sessionDid).unwrap { offer ->
                    indyUser().receiveClaimOffer(offer)
                    IndyUser.SchemaDetails(offer.schemaKey)
                }

                val issuerDid = subFlow(GetDidFlow.Initiator(issuer))

                val claimReq = indyUser().createClaimReq(schema, issuerDid, sessionDid, "master")
                flowSession.send(claimReq)
                logger.error("-----------------LOG-PROVER-SENT----------------")

                val flow = object : SignTransactionFlow(flowSession) {
                    override fun checkTransaction(stx: SignedTransaction) {
                        val output = stx.tx.toLedgerTransaction(serviceHub).outputs.singleOrNull()
                        val state = output!!.data
                        when(state) {
                            is IndyClaim -> {
                                logger.error("-----------------LOG-PROVER-VERIFICATION----------------")
                                require(state.claimReq == claimReq) { "Received incorrected ClaimReq"}
                                indyUser().receiveClaim(state.claim)
                                logger.error("-----------------LOG-PROVER-SAVED----------------")
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