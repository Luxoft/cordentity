package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyArtifactsRegistry
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.unwrap

/**
 * Utility flows to deal with an [IndyArtifactsRegistry] service
 * */
object ArtifactsRegistryFlow {

    /**
     * An utility flow to create and register an artifact in the Artifactory Service [artifactoryName]
     * */
    @InitiatingFlow
    class ArtifactCreator(private val artifactRequest: IndyArtifactsRegistry.PutRequest,
                          private val artifactoryName: CordaX500Name) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val flowSession: FlowSession = initiateFlow(whoIs(artifactoryName))
            flowSession.send(artifactRequest)
        }
    }

    /**
     * A flow to get `artifactId of an artifact registered in the Artifactory Service [artifactoryName]
     * that corresponds to filter [IndyArtifactsRegistry.CheckRequest.filter] in the [artifactRequest]
     *
     * @returns `artifactId` of an artifact matching the filter in [artifactRequest]
     * @throws  IllegalArgumentException if there is no matching artifact
     * */
    @InitiatingFlow
    class ArtifactAccessor(private val artifactRequest: IndyArtifactsRegistry.QueryRequest,
                           private val artifactoryName: CordaX500Name) : FlowLogic<String>() {
        @Suspendable
        override fun call():String {
            val flowSession: FlowSession = initiateFlow(whoIs(artifactoryName))
            return flowSession.sendAndReceive<String>(artifactRequest).unwrap { it }
        }
    }

    /**
     * A flow to check that there is an artifact registered in the Artifactory Service [artifactoryName]
     * that corresponds to filter [IndyArtifactsRegistry.CheckRequest.filter] in the [artifactRequest]
     *
     * @returns TRUE if is there is an artifact matching the filter in [artifactRequest]
     * */
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