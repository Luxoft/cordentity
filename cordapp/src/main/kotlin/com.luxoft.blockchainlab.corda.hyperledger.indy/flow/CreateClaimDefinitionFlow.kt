package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable

import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC

data class CreateClaimDefFlowResult(val credDefId: String, val revRegId: String)

/**
 * Flows to create a credential definition for a schema
 * */
object CreateClaimDefFlow {


    /**
     * A flow to create a credential definition for schema [schemaDetails]
     * and register it with an artifact registry [artifactoryName]
     *
     * @returns credential definition ID
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaId: String,
                    private val maxCredNumber: Int = 100
    ) : FlowLogic<CreateClaimDefFlowResult>() {

        @Suspendable
        override fun call(): CreateClaimDefFlowResult {
            try {
                // get schema Id from Artifactory
                val schemaId = getSchemaId(schemaDetails, artifactoryName)

                val credDef = indyUser().createClaimDefinition(schemaId, true)
                val credDefJson = SerializationUtils.anyToJSON(credDef)

                val revReg = indyUser().createRevocationRegistry(credDef, maxCredNumber)

                return CreateClaimDefFlowResult(credDef.id, revReg.definition.id)

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}