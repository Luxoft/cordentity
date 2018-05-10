package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC

object CreateSchemaFlow {

    @InitiatingFlow
    @StartableByRPC
    class Authority (
            private val schemaName: String,
            private val schemaVersion: String,
            private val schemaAttributes: List<String>
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val schemaDetails = IndyUser.SchemaDetails(schemaName, schemaVersion, indyUser().did)
                indyUser().createSchema(schemaDetails, schemaAttributes)
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}