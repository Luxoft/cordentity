package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*

/**
 * Flows to create an Indy scheme
 * */
object CreateSchemaFlow {

    /**
     * A flow to create an Indy scheme and register it with an artifact registry [artifactoryName]
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
                // create new schema
                return indyUser().createSchema(schemaName, schemaVersion, schemaAttributes).id
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}