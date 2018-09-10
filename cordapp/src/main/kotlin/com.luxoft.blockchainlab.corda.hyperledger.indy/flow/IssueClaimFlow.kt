package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimMetadataChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
import com.luxoft.blockchainlab.hyperledger.indy.ClaimOffer
import com.luxoft.blockchainlab.hyperledger.indy.ClaimRequestInfo
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

/**
 * Flows to issue Indy credentials
 * */
object IssueClaimFlow {

    /**
     * A flow to issue an Indy credential based on proposal [credProposal]
     *
     * [identifier] must be unique for the given Indy user to allow searching Credentials by `(identifier, issuerDID)`
     *
     * @param identifier        new unique ID for the new credential.
     *                          Must be unique for the given Indy user to allow searching Credentials by `(identifier, issuerDID)`
     *
     * @param credDefId         id of the credential definition to create new statement (credential)
     * @param credProposal      credential JSON containing attribute values for each of requested attribute names.
     *                          Example:
     *                          {
     *                            "attr1" : {"raw": "value1", "encoded": "value1_as_int" },
     *                            "attr2" : {"raw": "value1", "encoded": "value1_as_int" }
     *                          }
     *                          See `credValuesJson` in [org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateCredential]
     *
     * @param revRegId          Claim's revocation registry definition id
     * @param proverName        the node that can prove this credential
     *
     * @return                  claim id
     *
     * @note Flows starts by Issuer.
     * E.g User initially comes to university where asks for new education credential.
     * When user verification is completed the University runs IssueClaimFlow to produce required credential.
     * */
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
            private val identifier: String,
            private val credProposal: String,
            private val credDefId: String,
            private val proverName: CordaX500Name
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val prover: Party = whoIs(proverName)
            val flowSession: FlowSession = initiateFlow(prover)

            try {
                val credDefState = getIndyClaimDefinitionState(credDefId)
                    ?: throw RuntimeException("No indy claim definition with id=$credDefId in vault")

                val credDef = credDefState.state.data

                val offer = indyUser().createClaimOffer(credDef.claimDefId)

                val newClaimOut = flowSession.sendAndReceive<ClaimRequestInfo>(offer).unwrap { claimReq ->
                    verifyClaimAttributeValues(claimReq)
                    val claim = indyUser().issueClaim(claimReq, credProposal, offer, credDef.revRegId)
                    val claimOut = IndyClaim(identifier, claimReq, claim, indyUser().did, listOf(ourIdentity, prover))
                    StateAndContract(claimOut, ClaimChecker::class.java.name)
                }

                val newClaimData = ClaimChecker.Commands.Issue()
                val newClaimSigners = listOf(ourIdentity.owningKey, prover.owningKey)

                val newClaimCmd = Command(newClaimData, newClaimSigners)

                val claimDefCmdType = ClaimMetadataChecker.Command.Use()
                val claimDefCmd = Command(claimDefCmdType, newClaimSigners)

                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(newClaimOut, newClaimCmd, credDefState, claimDefCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(signedTrx))

            } catch (ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }

    @InitiatedBy(Issuer::class)
    open class Prover(private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val issuer = flowSession.counterparty.name

                val offer = flowSession.receive<ClaimOffer>().unwrap { offer -> offer }
                val sessionDid = subFlow(CreatePairwiseFlow.Prover(issuer))

                val claimRequestInfo = indyUser().createClaimRequest(sessionDid, offer)
                flowSession.send(claimRequestInfo)

                val flow = object : SignTransactionFlow(flowSession) {
                    override fun checkTransaction(stx: SignedTransaction) {
                        val outputs = stx.tx.toLedgerTransaction(serviceHub).outputs

                        outputs.forEach {
                            val state = it.data

                            when (state) {
                                is IndyClaim -> {
                                    require(state.claimRequestInfo == claimRequestInfo) { "Received incorrect ClaimReq" }
                                    indyUser().receiveClaim(state.claimInfo, state.claimRequestInfo, offer)
                                }
                                is IndyClaimDefinition -> println("Got indy claim definition")
                                else -> throw FlowException("invalid output state. IndyClaim is expected")
                            }
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