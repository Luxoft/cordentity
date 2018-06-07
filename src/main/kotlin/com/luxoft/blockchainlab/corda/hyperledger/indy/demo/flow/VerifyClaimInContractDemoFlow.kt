package com.luxoft.blockchainlab.corda.hyperledger.indy.demo.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimProof
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.contract.DemoClaimContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.schema.SchemaHappiness
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.state.SimpleStringState
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.model.Proof
import com.luxoft.blockchainlab.hyperledger.indy.model.ProofReq
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.security.PublicKey

object VerifyClaimInContractDemoFlow {

    @InitiatingFlow
    @StartableByRPC
    open class Verifier (
       private val prover: String,
       private val issuerDid: String
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean {
            return try {
                val otherSide: Party = serviceHub.identityService.partiesFromName(prover, true).single()
                val flowSession: FlowSession = initiateFlow(otherSide)

                val schema = SchemaHappiness()
                val schemaDetails = IndyUser.SchemaDetails(schema.getSchemaName(), schema.getSchemaVersion(), issuerDid)

                // Love doesn't care about age
                val attributes = listOf( IndyUser.ProofAttribute(schemaDetails, schema.schemaAttrForKiss,"value"))
                // But you can drink only after 21
                val predicates = listOf( IndyUser.ProofPredicate(schemaDetails, schema.schemaAttrForDrink, 21) )

                val proofRequest = indyUser().createProofReq(attributes, predicates)
                val proof = flowSession.sendAndReceive<Proof>(proofRequest).unwrap { it }

                val claimProofInput = StateAndContract(IndyClaimProof(proofRequest, proof, listOf(ourIdentity, otherSide)), DemoClaimContract::class.java.name)
                val kissState = StateAndContract(SimpleStringState("moi-moi-moi", otherSide), DemoClaimContract::class.java.name)
                val drinkState = StateAndContract(SimpleStringState("gulp", otherSide), DemoClaimContract::class.java.name)

                val commandData: DemoClaimContract.Commands.Verify = DemoClaimContract.Commands.Verify()
                val ourPubKey: PublicKey = serviceHub.myInfo.legalIdentitiesAndCerts.first().owningKey
                val counterpartyPubKey: PublicKey = otherSide.owningKey
                val requiredSigners: List<PublicKey> = listOf(ourPubKey, counterpartyPubKey)
                val verifyCommand: Command<DemoClaimContract.Commands.Verify> = Command(commandData, requiredSigners)

                val notary: Party = serviceHub.networkMapCache.notaryIdentities.first()

                val txBuilder = TransactionBuilder(notary)

                txBuilder.withItems(
                        // Outputs
                        claimProofInput,

                        kissState,
                        drinkState,
                        // Commands
                        verifyCommand
                )

                txBuilder.toWireTransaction(serviceHub).toLedgerTransaction(serviceHub).verify()

                // Sign the transaction.
                val selfSignedTx = serviceHub.signInitialTransaction(txBuilder)

                val fullySignedTx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(fullySignedTx))

                true
            } catch (e: Exception) {
                logger.error("", e)
                false
            }
        }
    }

    @InitiatedBy(Verifier::class)
    open class Prover (private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                flowSession.receive(ProofReq::class.java).unwrap { indyProofReq ->
                    // TODO: Master Secret should be received from the outside
                    val masterSecret = indyUser().masterSecret
                    flowSession.send(indyUser().createProof(indyProofReq, masterSecret))
                }

                val signTransactionFlow = object : SignTransactionFlow(flowSession) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        // Check general validity
                        DemoClaimContract().verify(stx.toLedgerTransaction(serviceHub, false))
                    }
                }
                subFlow(signTransactionFlow)

            } catch (e: Exception) {
                logger.error("", e)
                throw FlowException(e)
            }
        }
    }
}