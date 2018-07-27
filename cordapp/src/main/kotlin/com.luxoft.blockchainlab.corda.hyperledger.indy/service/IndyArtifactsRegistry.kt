package com.luxoft.blockchainlab.corda.hyperledger.indy.service

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.ArtifactsRegistryFlow
import com.luxoft.blockchainlab.hyperledger.indy.model.CredentialDefinition
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken

import com.luxoft.blockchainlab.hyperledger.indy.model.Schema
import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.unwrap
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A temporal substitute for a global Schema and Credential Definition discovery facility.
 *
 * Ideally a Schema catalog should be external to the system and available to all interested party.
 * Since Hyperledger has not yet provided such a facility,
 * [IndyArtifactsRegistry] should be enough for simple demos and POCs.
 * */
object IndyArtifactsRegistry {

    @CordaSerializable
    enum class ARTIFACT_TYPE { Schema, Definition }

    @CordaSerializable
    data class QueryRequest(val type: ARTIFACT_TYPE, val filter: String)

    @CordaSerializable
    data class PutRequest(val type: ARTIFACT_TYPE, val payloadJson: JSONObject)

    @CordaSerializable
    data class CheckRequest(val type: ARTIFACT_TYPE, val filter: String)

//    @InitiatedBy(ArtifactsRegistryFlow.ArtifactAccessor::class)
//    class SignHandler(private val issuer: FlowSession) : FlowLogic<Unit>() {
//        @Suspendable
//        override fun call() {
//        }
//    }

    @InitiatedBy(ArtifactsRegistryFlow.ArtifactAccessor::class)
    class QueryHandler(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                val queryRequest = flowSession.receive<QueryRequest>().unwrap { it }

                val artifacts = serviceHub.cordaService(ArtifactsRegistry::class.java)

                val artifactId = when (queryRequest.type) {
                    ARTIFACT_TYPE.Schema -> artifacts.schemaRegistry.get(queryRequest.filter)
                    ARTIFACT_TYPE.Definition -> artifacts.credsDefRegistry.get(queryRequest.filter)
                    else -> throw FlowException("unknown indy artifact query request: " +
                            "${queryRequest.type}, ${queryRequest.filter}")
                }

                requireNotNull(artifactId) {
                    "Artifact wasnt found in registry: " +
                            "${queryRequest.type}, ${queryRequest.filter}"
                }

                flowSession.send(artifactId!!)
            } catch(t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }

    @InitiatedBy(ArtifactsRegistryFlow.ArtifactCreator::class)
    class PutHandler(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                val putRequest = flowSession.receive<PutRequest>().unwrap { it }

                val artifacts = serviceHub.cordaService(ArtifactsRegistry::class.java)

                when (putRequest.type) {
                    ARTIFACT_TYPE.Schema -> artifacts.addSchema(Schema(putRequest.payloadJson))
                    ARTIFACT_TYPE.Definition -> artifacts.addCredentialDef(CredentialDefinition(putRequest.payloadJson))
                    else -> throw FlowException("unknown indy artifact put request: " +
                            "${putRequest.type}, ${putRequest.payloadJson}")
                }
            } catch(t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }

    @InitiatedBy(ArtifactsRegistryFlow.ArtifactVerifier::class)
    class CheckHandler(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                val checkRequest = flowSession.receive<CheckRequest>().unwrap { it }

                val artifacts = serviceHub.cordaService(ArtifactsRegistry::class.java)

                val isExist = when(checkRequest.type) {
                    ARTIFACT_TYPE.Schema -> artifacts.schemaRegistry.contains(checkRequest.filter)
                    ARTIFACT_TYPE.Definition -> artifacts.credsDefRegistry.contains(checkRequest.filter)
                    else -> throw FlowException("unknown indy artifact check request: " +
                            "${checkRequest.type}, ${checkRequest.filter}")
                }

                flowSession.send(isExist)
            } catch(t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }

    @CordaService
    class ArtifactsRegistry(services: AppServiceHub): SingletonSerializeAsToken() {

        val schemaRegistry = ConcurrentHashMap<String, String>()
        val credsDefRegistry = ConcurrentHashMap<String, String>()

        fun addSchema(schema: Schema) =
                schemaRegistry.putIfAbsent(schema.filter, schema.id)

        fun addCredentialDef(credentialDef: CredentialDefinition) =
                credsDefRegistry.putIfAbsent(credentialDef.filter, credentialDef.id)
    }
}