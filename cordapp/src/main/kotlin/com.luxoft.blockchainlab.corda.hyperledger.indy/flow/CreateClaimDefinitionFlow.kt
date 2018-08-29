package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC

/**
 * An abstraction over flow result
 *
 * @param credDefId         Credential definition id
 * @param revRegId          Revocation registry id
 */
data class CreateClaimDefinitionFlowResult(val credDefId: String, val revRegId: String)

/**
 * Flow to create a credential definition and revocation registry for a schema
 * */
object CreateClaimDefinitionFlow {

    /**
     * @param schemaId          Id of target schema
     * @param maxCredNumber     Maximum number of issued claims per schema
     *
     * @returns credential definition ID and revocation registry ID
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaId: String, private val maxCredNumber: Int = 100) : FlowLogic<CreateClaimDefinitionFlowResult>() {

        @Suspendable
        override fun call(): CreateClaimDefinitionFlowResult {
            try {
                val credDef = indyUser().createClaimDefinition(schemaId, true)
                val revReg = indyUser().createRevocationRegistry(credDef.id, maxCredNumber)

                return CreateClaimDefinitionFlowResult(credDef.id, revReg.definition.id)

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}