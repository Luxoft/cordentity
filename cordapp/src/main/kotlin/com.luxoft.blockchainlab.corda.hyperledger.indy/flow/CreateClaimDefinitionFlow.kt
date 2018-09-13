package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.ClaimMetadataChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.SchemaChecker
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
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
                val schemaFromVault = getIndySchemaState(schemaId)
                    ?: throw RuntimeException("There is no schema with id: $schemaId in vault")

                val credDef = indyUser().createClaimDefinition(schemaId, true)
                val revReg = indyUser().createRevocationRegistry(credDef.id, maxCredNumber)

                val claimDef = IndyClaimDefinition(schemaId, credDef.id, revReg.definition.id, listOf(ourIdentity))
                val claimDefOut = StateAndContract(claimDef, ClaimMetadataChecker::class.java.name)

                val commandType = ClaimMetadataChecker.Command.Create()
                val signers = listOf(ourIdentity.owningKey)

                val command = Command(commandType, signers)

                val schemaCmdType = SchemaChecker.Command.Use()
                val schemaCmd = Command(schemaCmdType, signers)

                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .withItems(claimDefOut, command, schemaFromVault, schemaCmd)

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                subFlow(FinalityFlow(selfSignedTx))

                return claimDef.claimDefId

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}