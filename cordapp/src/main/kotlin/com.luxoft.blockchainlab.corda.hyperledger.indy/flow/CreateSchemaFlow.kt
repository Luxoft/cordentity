package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyArtifactsRegistry
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name

object CreateSchemaFlow {

    @InitiatingFlow
    @StartableByRPC
    class Authority (
            private val schemaName: String,
            private val schemaVersion: String,
            private val schemaAttributes: List<String>,
            private val artifactoryName: CordaX500Name
    ) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                val schemaDetails = IndyUser.SchemaDetails(schemaName, schemaVersion, indyUser().did)

                val checkReq = IndyArtifactsRegistry.CheckRequest(
                        IndyArtifactsRegistry.ARTIFACT_TYPE.Schema, schemaDetails.filter)
                val isExist = subFlow(ArtifactsRegistryFlow.ArtifactVerifier(checkReq, artifactoryName))

                return if(isExist) {
                    // return schema id from ArtifactsRegistry
                    getSchemaId(schemaDetails, artifactoryName)
                } else {
                    // create new schema and add it to ArtifactsRegistry
                    val schema = indyUser().createSchema(schemaName, schemaVersion, schemaAttributes)

                    // put schema on Artifactory
                    val schemaReq = IndyArtifactsRegistry.PutRequest(
                            IndyArtifactsRegistry.ARTIFACT_TYPE.Schema, schema.json)
                    subFlow(ArtifactsRegistryFlow.ArtifactCreator(schemaReq, artifactoryName))

                    schema.id
                }

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}