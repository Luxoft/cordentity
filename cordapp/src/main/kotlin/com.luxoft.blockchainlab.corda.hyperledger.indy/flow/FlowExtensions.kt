package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyArtifactsRegistry
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.ClaimRequestInfo
import com.luxoft.blockchainlab.hyperledger.indy.CredentialDefDetails
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.SchemaDetails
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party


/**
 * Extension methods to reduce boilerplate code in Indy flows
 */

fun FlowLogic<Any>.whoIs(x509: CordaX500Name): Party {
    return serviceHub.identityService.wellKnownPartyFromX500Name(x509)!!
}

fun FlowLogic<Any>.whoIsNotary(): Party {
    return serviceHub.networkMapCache.notaryIdentities.single()
}

fun FlowLogic<Any>.indyUser(): IndyUser {

    return serviceHub.cordaService(IndyService::class.java).indyUser
}

fun FlowLogic<Any>.verifyClaimAttributeValues(claimRequest: ClaimRequestInfo): Boolean {

    return serviceHub.cordaService(IndyService::class.java).claimAttributeValuesChecker.verifyRequestedClaimAttributes(claimRequest)
}


@Suspendable
fun FlowLogic<Any>.getSchemaId(
        schemaDetails: SchemaDetails,
        artifactoryName: CordaX500Name): String {

    val schemaReq = IndyArtifactsRegistry.QueryRequest(
            IndyArtifactsRegistry.ARTIFACT_TYPE.Schema, schemaDetails.filter)
    return subFlow(ArtifactsRegistryFlow.ArtifactAccessor(schemaReq, artifactoryName))
}

@Suspendable
fun FlowLogic<Any>.getCredDefId(
        schemaDetails: SchemaDetails,
        credDefOwner: String,
        artifactoryName: CordaX500Name): String {

    // get schema identifier
    val schemaId = getSchemaId(schemaDetails, artifactoryName)

    // get schema from public Indy ledger
    val schema = indyUser().getSchema(schemaId)

    // get credential identifier
    val credDef = CredentialDefDetails(schema.seqNo.toString(), credDefOwner)
    val credDefReq = IndyArtifactsRegistry.QueryRequest(
            IndyArtifactsRegistry.ARTIFACT_TYPE.Definition, credDef.filter)
    return subFlow(ArtifactsRegistryFlow.ArtifactAccessor(credDefReq, artifactoryName))
}

@Suspendable
fun FlowLogic<Any>.getCredDefId(
        schemaId: String,
        credDefOwner: String,
        artifactoryName: CordaX500Name): String {

    // get schema from public Indy ledger
    val schema = indyUser().getSchema(schemaId)

    // get credential identifier
    val credDef = CredentialDefDetails(schema.seqNo.toString(), credDefOwner)
    val credDefReq = IndyArtifactsRegistry.QueryRequest(
            IndyArtifactsRegistry.ARTIFACT_TYPE.Definition, credDef.filter)
    return subFlow(ArtifactsRegistryFlow.ArtifactAccessor(credDefReq, artifactoryName))
}