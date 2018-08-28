package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyArtifactsRegistry
import com.luxoft.blockchainlab.hyperledger.indy.SchemaDetails
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name

data class CreateClaimDefFlowResult(val credDefId: String, val revRegId: String)

/**
 * A flow to create a credential definition for schema [schemaDetails] and register it with an artifact registry [artifactoryName]
 * */
object CreateClaimDefFlow {

    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaDetails: SchemaDetails,
                    private val maxCredNumber: Int = 100,
                    private val artifactoryName: CordaX500Name) : FlowLogic<CreateClaimDefFlowResult>() {

        @Suspendable
        override fun call(): CreateClaimDefFlowResult {
            try {
                // get schema Id from Artifactory
                val schemaId = getSchemaId(schemaDetails, artifactoryName)

                // TODO: check if claimDef already exist

                val credDef = indyUser().createClaimDefinition(schemaId, true)
                val credDefJson = SerializationUtils.anyToJSON(credDef)

                // put definition on Artifactory
                val definitionReq = IndyArtifactsRegistry.PutRequest(
                        IndyArtifactsRegistry.ARTIFACT_TYPE.Definition, credDefJson
                )
                subFlow(ArtifactsRegistryFlow.ArtifactCreator(definitionReq, artifactoryName))

                val revReg = indyUser().createRevocationRegistry(credDef, maxCredNumber)

                return CreateClaimDefFlowResult(credDef.id, revReg.definition.id)

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}