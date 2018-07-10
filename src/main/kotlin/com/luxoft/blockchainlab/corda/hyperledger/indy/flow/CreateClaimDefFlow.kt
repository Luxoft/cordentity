package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC


object CreateClaimDefFlow {

    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaId: String) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                val credDefId = indyUser().createClaimDef(schemaId)
                return credDefId
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}