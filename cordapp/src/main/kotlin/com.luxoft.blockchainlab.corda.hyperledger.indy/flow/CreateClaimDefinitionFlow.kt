package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndySchemaContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialDefinitionAlreadyExistsException
import com.luxoft.blockchainlab.hyperledger.indy.IndySchemaNotFoundException
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder


/**
 * Flow to create a credential definition and revocation registry for a schema
 * */
object CreateClaimDefinitionFlow {

    /**
     * @param schemaId             Id of target schema
     * @param credentialsLimit     Maximum number of possible credentials issued per definition
     *
     * @returns                    credential definition persistent id
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaId: String, private val credentialsLimit: Int = 100) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                checkNoCredentialDefinitionOnCorda()
                checkNoCredentialDefinitionOnIndy()

                // create indy stuff
                val credentialDefinitionObj = indyUser().createClaimDefinition(schemaId, true)
                val revocationRegistry = indyUser().createRevocationRegistry(credentialDefinitionObj.id, credentialsLimit)

                val signers = listOf(ourIdentity.owningKey)

                // create new credential definition state
                val credentialDefinition = IndyClaimDefinition(
                        schemaId,
                        credentialDefinitionObj.id,
                        revocationRegistry.definition.id,
                        credentialsLimit,
                        listOf(ourIdentity)
                )
                val credentialDefinitionOut = StateAndContract(credentialDefinition, IndyCredentialDefinitionContract::class.java.name)
                val credentialDefinitionCmdType = IndyCredentialDefinitionContract.Command.Create()
                val credentialDefinitionCmd = Command(credentialDefinitionCmdType, signers)

                // consume old schema state
                val schemaIn = getSchemaById(schemaId)
                        ?: throw IndySchemaNotFoundException(schemaId, "Corda does't have proper schema in vault")

                val schemaOut = StateAndContract(schemaIn.state.data, IndySchemaContract::class.java.name)
                val schemaCmdType = IndySchemaContract.Command.Consume()
                val schemaCmd = Command(schemaCmdType, signers)

                // do stuff
                val trxBuilder = TransactionBuilder(whoIsNotary()).withItems(
                        schemaIn,
                        credentialDefinitionOut,
                        credentialDefinitionCmd,
                        schemaOut,
                        schemaCmd)

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                subFlow(FinalityFlow(selfSignedTx))

                return credentialDefinition.claimDefId

            } catch (t: Throwable) {
                logger.error("New credential definition has been failed", t)
                throw FlowException(t.message)
            }
        }

        private fun checkNoCredentialDefinitionOnCorda() {
            getSchemaById(schemaId)
                    ?: throw IndySchemaNotFoundException(schemaId, "Corda does't have proper states")

            if (getCredentialDefinitionBySchemaId(schemaId) != null) {
                throw IndyCredentialDefinitionAlreadyExistsException(schemaId,
                        "Credential definition already exist on Corda ledger")
            }
        }

        private fun checkNoCredentialDefinitionOnIndy() {
            if(indyUser().isCredentialDefinitionExist(schemaId))
                throw IndyCredentialDefinitionAlreadyExistsException(schemaId,
                        "Credential definition already exist on Indy ledger")
        }
    }
}