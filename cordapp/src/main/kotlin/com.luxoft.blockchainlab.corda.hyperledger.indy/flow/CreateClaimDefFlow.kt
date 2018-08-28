package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable

import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC


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
    class Authority(private val schemaId: String) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                return indyUser().createClaimDef(schemaId).id
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}