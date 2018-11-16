package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredential
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialDefinition
import com.luxoft.blockchainlab.hyperledger.indy.*
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
object IssueCredentialFlow {

    /**
     * A flow to issue an Indy credential based on proposal [credProposal]
     *
     * @param credentialDefinitionId    id of the credential definition to create new statement (credential)
     * @param credentialProposal        credential JSON containing attribute values for each of requested attribute names.
     *                                  Example:
     *                                  {
     *                                      "attr1" : {"raw": "value1", "encoded": "value1_as_int" },
     *                                      "attr2" : {"raw": "value1", "encoded": "value1_as_int" }
     *                                  }
     *                                  See `credValuesJson` in [org.hyperledger.indy.sdk.anoncreds.Anoncreds.issuerCreateCredential]
     *
     * @param proverName                the node that can prove this credential
     *
     * @return                          credential id
     *
     * @note Flows starts by Issuer.
     * E.g User initially comes to university where asks for new education credential.
     * When user verification is completed the University runs IssueCredentialFlow to produce required credential.
     * */
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
        private val credentialProposal: String,
        private val credentialDefinitionId: CredentialDefinitionId,
        private val proverName: CordaX500Name
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val prover: Party = whoIs(proverName)
            val flowSession: FlowSession = initiateFlow(prover)

            try {
                // checking if cred def exists and can produce new credentials
                val originalCredentialDefIn = getCredentialDefinitionById(credentialDefinitionId)
                    ?: throw IndyCredentialDefinitionNotFoundException(
                        credentialDefinitionId.toString(),
                        "State doesn't exist in Corda vault"
                    )
                val originalCredentialDef = originalCredentialDefIn.state.data

                if (!originalCredentialDef.canProduceCredentials())
                    throw IndyCredentialMaximumReachedException(
                        originalCredentialDef.credentialDefinitionId.getRevocationRegistryDefinitionId(
                            IndyUser.REVOCATION_TAG
                        ).toString()
                    )

                // issue credential
                val offer =
                    indyUser().createCredentialOffer(credentialDefinitionId)

                val signers = listOf(ourIdentity.owningKey, prover.owningKey)
                val newCredentialOut =
                    flowSession.sendAndReceive<CredentialRequestInfo>(offer).unwrap { credentialReq ->
                        val credential = indyUser().issueCredential(
                            credentialReq,
                            credentialProposal,
                            offer
                        )
                        val credentialOut = IndyCredential(
                            credentialReq,
                            credential,
                            indyUser().did,
                            credentialReq.request.proverDid,
                            listOf(ourIdentity, prover)
                        )
                        StateAndContract(credentialOut, IndyCredentialContract::class.java.name)
                    }
                val newCredentialCmdType = IndyCredentialContract.Command.Issue()
                val newCredentialCmd = Command(newCredentialCmdType, signers)

                // consume credential definition
                val credentialDefinition = originalCredentialDef.requestNewCredential()
                val credentialDefinitionOut =
                    StateAndContract(credentialDefinition, IndyCredentialDefinitionContract::class.java.name)
                val credentialDefinitionCmdType = IndyCredentialDefinitionContract.Command.Consume()
                val credentialDefinitionCmd = Command(credentialDefinitionCmdType, signers)

                // do stuff
                val trxBuilder = TransactionBuilder(whoIsNotary()).withItems(
                    originalCredentialDefIn,
                    newCredentialOut,
                    newCredentialCmd,
                    credentialDefinitionOut,
                    credentialDefinitionCmd
                )

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
                val offer = flowSession.receive<CredentialOffer>().unwrap { offer -> offer }

                val credentialRequestInfo =
                    indyUser().createCredentialRequest(indyUser().did, offer, indyUser().defaultMasterSecretId)
                flowSession.send(credentialRequestInfo)

                val flow = object : SignTransactionFlow(flowSession) {
                    override fun checkTransaction(stx: SignedTransaction) {
                        val outputs = stx.tx.toLedgerTransaction(serviceHub).outputs

                        outputs.forEach {
                            val state = it.data

                            when (state) {
                                is IndyCredential -> {
                                    require(state.credentialRequestInfo == credentialRequestInfo) { "Received incorrect CredentialRequest" }
                                    indyUser().receiveCredential(
                                        state.credentialInfo,
                                        state.credentialRequestInfo,
                                        offer
                                    )
                                }
                                is IndyCredentialDefinition -> logger.info("Got indy credential definition")
                                else -> throw FlowException("invalid output state. IndyCredential is expected")
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