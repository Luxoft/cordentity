package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyArtifactsRegistry
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.unwrap

/**
 * An utility flow to register a new [IndyArtifactsRegistry] service
 * in the Corda network under the [artifactoryName] name
 * */
object ArtifactsRegistryFlow {

    @InitiatingFlow
    class ArtifactCreator(private val artifactRequest: IndyArtifactsRegistry.PutRequest,
                          private val artifactoryName: CordaX500Name) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val flowSession: FlowSession = initiateFlow(whoIs(artifactoryName))
            flowSession.send(artifactRequest)
        }
    }

    @InitiatingFlow
    class ArtifactAccessor(private val artifactRequest: IndyArtifactsRegistry.QueryRequest,
                           private val artifactoryName: CordaX500Name) : FlowLogic<String>() {
        @Suspendable
        override fun call():String {
            val flowSession: FlowSession = initiateFlow(whoIs(artifactoryName))
            return flowSession.sendAndReceive<String>(artifactRequest).unwrap { it }
        }
    }

    @InitiatingFlow
    class ArtifactVerifier(private val artifactRequest: IndyArtifactsRegistry.CheckRequest,
                           private val artifactoryName: CordaX500Name) : FlowLogic<Boolean>() {
        @Suspendable
        override fun call(): Boolean {
            val flowSession: FlowSession = initiateFlow(whoIs(artifactoryName))
            return flowSession.sendAndReceive<Boolean>(artifactRequest).unwrap { it }
        }
    }
}