package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimMetadataChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.SchemaChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndySchema
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder

/**
 * Flows to create an Indy scheme
 * */
object CreateSchemaFlow {

    /**
     * A flow to create an Indy scheme
     *
     * @param schemaName        name of the new schema
     * @param schemaVersion     version of the schema
     * @param schemaAttributes  a list of attribute names
     * @returns                 Schema ID
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(
            private val schemaName: String,
            private val schemaVersion: String,
            private val schemaAttributes: List<String>
    ) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {

                val schema = indyUser().createSchema(schemaName, schemaVersion, schemaAttributes)
                val indySchema = IndySchema(schema.id, listOf(ourIdentity))
                val schemaOut = StateAndContract(indySchema, SchemaChecker::class.java.name)

                val commandType = SchemaChecker.Command.Create()
                val signers = listOf(ourIdentity.owningKey)
                val command = Command(commandType, signers)

                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .withItems(schemaOut, command)

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                subFlow(FinalityFlow(selfSignedTx))

                return schema.id

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}