package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimDefinitionSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.IndySchemaSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndySchema
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.ClaimRequestInfo
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.GenericQueryCriteria
import net.corda.core.node.services.vault.QueryCriteria


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

/**
 * This method is used to get indy claim state from vault
 *
 * @param claimId           id of claim
 *
 * @return                  corda state of indy claim or null if none exists
 */
fun FlowLogic<Any>.getIndyClaimState(claimId: String): StateAndRef<IndyClaim>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val id = QueryCriteria.VaultCustomQueryCriteria(ClaimSchemaV1.PersistentClaim::id.equal(claimId))

    val criteria = generalCriteria.and(id)
    val result = serviceHub.vaultService.queryBy<IndyClaim>(criteria)

    return result.states.firstOrNull()
}

private fun FlowLogic<Any>.getUnconsumedCredentialDefinitionByCriteria(
        criteria: QueryCriteria.VaultCustomQueryCriteria<ClaimDefinitionSchemaV1.PersistentClaimDefinition>)
        : StateAndRef<IndyClaimDefinition>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

    val criteria = generalCriteria.and(criteria)
    val result = serviceHub.vaultService.queryBy<IndyClaimDefinition>(criteria)

    return result.states.firstOrNull()
}

fun FlowLogic<Any>.getCredentialDefinitionById(credentialDefinitionId: String): StateAndRef<IndyClaimDefinition>? {
    return getUnconsumedCredentialDefinitionByCriteria(QueryCriteria.VaultCustomQueryCriteria(
            ClaimDefinitionSchemaV1.PersistentClaimDefinition::claimDefId.equal(credentialDefinitionId)))
}

fun FlowLogic<Any>.getCredentialDefinitionBySchemaId(schemaId: String): StateAndRef<IndyClaimDefinition>? {
    return getUnconsumedCredentialDefinitionByCriteria(QueryCriteria.VaultCustomQueryCriteria(
            ClaimDefinitionSchemaV1.PersistentClaimDefinition::schemaId.equal(schemaId)))
}

fun FlowLogic<Any>.getSchemaById(schemaId: String): StateAndRef<IndySchema>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val id = QueryCriteria.VaultCustomQueryCriteria(IndySchemaSchemaV1.PersistentSchema::id.equal(schemaId))

    val criteria = generalCriteria.and(id)
    val result = serviceHub.vaultService.queryBy<IndySchema>(criteria)

    return result.states.firstOrNull()
}