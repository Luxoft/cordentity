package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
import com.luxoft.blockchainlab.hyperledger.indy.ClaimOffer
import com.luxoft.blockchainlab.hyperledger.indy.ClaimRequestInfo
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialDefinitionNotFoundException
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialMaximumReachedException
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
                // checking if cred def exists and can produce new credentials
                val originalCredentialDefIn = getCredentialDefinitionById(credDefId)
                    ?: throw IndyCredentialDefinitionNotFoundException(credDefId, "State doesn't exist in Corda vault")
                val originalCredentialDef = originalCredentialDefIn.state.data

                if (!originalCredentialDef.canProduceCredentials())
                    throw IndyCredentialMaximumReachedException(originalCredentialDef.revRegId)

                // issue credential
                val offer = indyUser().createClaimOffer(originalCredentialDef.claimDefId)

                val signers = listOf(ourIdentity.owningKey, prover.owningKey)
                val newCredentialOut = flowSession.sendAndReceive<ClaimRequestInfo>(offer).unwrap { claimReq ->
                    val claim = indyUser().issueClaim(claimReq, credProposal, offer, originalCredentialDef.revRegId)
                    val claimOut = IndyClaim(identifier, claimReq, claim, indyUser().did, listOf(ourIdentity, prover))
                    StateAndContract(claimOut, IndyCredentialContract::class.java.name)
                }
                val newCredentialCmdType = IndyCredentialContract.Command.Issue()
                val newCredentialCmd = Command(newCredentialCmdType, signers)

                // consume credential definition
                val credentialDefinition = originalCredentialDef.requestNewCredential()
                val credentialDefinitionOut = StateAndContract(credentialDefinition, IndyCredentialDefinitionContract::class.java.name)
                val credentialDefinitionCmdType = IndyCredentialDefinitionContract.Command.Consume()
                val credentialDefinitionCmd = Command(credentialDefinitionCmdType, signers)

                // do stuff
                val trxBuilder = TransactionBuilder(whoIsNotary()).withItems(
                        originalCredentialDefIn,
                        newCredentialOut,
                        newCredentialCmd,
                        credentialDefinitionOut,
                        credentialDefinitionCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(signedTrx))

            } catch (ex: Exception) {
                logger.error("Credential has not been issued", ex)
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
                                is IndyClaimDefinition -> logger.info("Got indy claim definition")
                                else -> throw FlowException("invalid output state. IndyClaim is expected")
                            }
                        }
                    }
                }

                subFlow(flow)

            } catch (ex: Exception) {
                logger.error("Credential has not been issued", ex)
                throw FlowException(ex.message)
            }
        }
    }
}