package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*

/**
 * A flow to create an Indy scheme and register it with an artifact registry [artifactoryName]
 * */
object CreateSchemaFlow {

    @InitiatingFlow
    @StartableByRPC
    class Authority (
            private val schemaName: String,
            private val schemaVersion: String,
            private val schemaAttributes: List<String>
    ) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                // create new schema
                return indyUser().createSchema(schemaName, schemaVersion, schemaAttributes).id
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}