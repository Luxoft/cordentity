package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimChecker
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder


object RevokeClaimFlow {
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
            private val revRegId: String,
            private val credRevId: String
    ) : FlowLogic<Unit>() {
        override fun call() {
            try {
                indyUser().revokeClaim(revRegId, credRevId)

                val newClaimData = ClaimChecker.Commands.Revoke()
                val newClaimSigners = listOf(ourIdentity.owningKey)

                val newClaimCmd = Command(newClaimData, newClaimSigners)

                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(newClaimCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                // Notarise and record the transaction by itself.
                subFlow(FinalityFlow(selfSignedTx))

            } catch(ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }

    @InitiatedBy(Issuer::class)
    open class Prover(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        override fun call() {
        }
    }
}