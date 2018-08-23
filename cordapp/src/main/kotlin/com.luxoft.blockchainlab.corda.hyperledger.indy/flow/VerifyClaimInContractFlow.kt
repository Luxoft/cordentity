package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimProof
import com.luxoft.blockchainlab.hyperledger.indy.CredFieldRef
import com.luxoft.blockchainlab.hyperledger.indy.CredPredicate
import com.luxoft.blockchainlab.hyperledger.indy.ProofInfo
import com.luxoft.blockchainlab.hyperledger.indy.ProofRequest
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

/** WORK IN PROGRESS */
object VerifyClaimInContractFlow {

    @InitiatingFlow
    @StartableByRPC
    open class Verifier (
            private val identifier: String,
            private val attributes: List<VerifyClaimFlow.ProofAttribute>,
            private val predicates: List<VerifyClaimFlow.ProofPredicate>,
            private val proverName: CordaX500Name,
            private val artifactoryName: CordaX500Name
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean  {
            try {
                val prover: Party = whoIs(proverName)
                val flowSession: FlowSession = initiateFlow(prover)

                val proofRequest = indyUser().createProofReq(fieldRefFromAttributes(attributes), fieldRefFromPredicates(predicates))

                val verifyClaimOut = flowSession.sendAndReceive<ProofInfo>(proofRequest).unwrap { proof ->
                    val claimProofOut = IndyClaimProof(identifier, proofRequest, proof, listOf(ourIdentity, prover))
                    StateAndContract(claimProofOut, ClaimChecker::class.java.name)
                }

                val expectedAttrs = attributes
                        .filter { it.value.isNotEmpty() }
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

        private fun fieldRefFromAttributes(attributes: List<VerifyClaimFlow.ProofAttribute>) = attributes.map {
            val schemaId = getSchemaId(it.schemaDetails, artifactoryName)
            val credDefId = getCredDefId(schemaId, it.credDefOwner, artifactoryName)

            CredFieldRef(it.field, schemaId, credDefId)
        }

        private fun fieldRefFromPredicates(predicates: List<VerifyClaimFlow.ProofPredicate>) = predicates.map {
            val schemaId = getSchemaId(it.schemaDetails, artifactoryName)
            val credDefId = getCredDefId(schemaId, it.credDefOwner, artifactoryName)

            val fieldRef = CredFieldRef(it.field, schemaId, credDefId)

            CredPredicate(fieldRef, it.value)
        }
    }

    @InitiatedBy(VerifyClaimInContractFlow.Verifier::class)
    open class Prover (private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                flowSession.receive(ProofRequest::class.java).unwrap { indyProofReq ->
                    // TODO: Master Secret should be received from the outside
                    val masterSecretId = indyUser().defaultMasterSecretId
                    flowSession.send(indyUser().createProof(indyProofReq, masterSecretId))
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