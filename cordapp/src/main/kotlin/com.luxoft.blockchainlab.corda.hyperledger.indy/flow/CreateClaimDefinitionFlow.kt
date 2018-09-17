package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndySchemaContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder


/**
 * Flow to create a credential definition and revocation registry for a schema
 * */
object CreateClaimDefinitionFlow {

    /**
     * @param schemaId          Id of target schema
     * @param maxCredNumber     Maximum number of issued claims per schema
     *
     * @returns                 claim definition persistent id
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaId: String, private val maxCredNumber: Int = 100) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                // checking if credential definition already exists
                val schema = indyUser().retrieveSchema(schemaId)
                    ?: throw RuntimeException("There is no schema with id: $schemaId in ledger")
                val credDefId = IndyUser.buildCredentialDefinitionId(indyUser().did, schema.seqNo!!)
                val credDefFromLedger = indyUser().retrieveClaimDefinition(credDefId)
                if (credDefFromLedger != null) {
                    throw IndyCredentialDefinitionAlreadyExistsException(credDefId)
                }

                // create indy stuff
                val credDef = indyUser().createClaimDefinition(schemaId, true)
                val revReg = indyUser().createRevocationRegistry(credDef.id, maxCredNumber)

                val signers = listOf(ourIdentity.owningKey)

                // create new credential definition state
                val credDefState = IndyClaimDefinition(
                    schemaId,
                    credDef.id,
                    revReg.definition.id,
                    0,
                    maxCredNumber,
                    listOf(ourIdentity)
                )
                val credDefOut = StateAndContract(credDefState, IndyCredentialDefinitionContract::class.java.name)
                val credDefCmdType = IndyCredentialDefinitionContract.Command.Create()
                val credDefCmd = Command(credDefCmdType, signers)

                // consume old schema state
                val schemaStateIn = getIndySchemaState(schemaId)
                    ?: throw RuntimeException("There is no schema with id: $schemaId in vault")
                val schemaStateOut = StateAndContract(schemaStateIn.state.data, IndySchemaContract::class.java.name)
                val schemaCmdType = IndySchemaContract.Command.Consume()
                val schemaCmd = Command(schemaCmdType, signers)

                // do stuff
                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .withItems(credDefOut, credDefCmd, schemaStateIn, schemaStateOut, schemaCmd)

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                subFlow(FinalityFlow(selfSignedTx))

                return credDefId

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}