package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimDefinitionSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
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

fun FlowLogic<Any>.verifyClaimAttributeValues(claimRequest: ClaimRequestInfo): Boolean {

    return serviceHub.cordaService(IndyService::class.java).claimAttributeValuesChecker.verifyRequestedClaimAttributes(claimRequest)
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

fun FlowLogic<Any>.getIndyClaimDefinitionState(claimDefId: String): StateAndRef<IndyClaimDefinition>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val id = QueryCriteria.VaultCustomQueryCriteria(ClaimDefinitionSchemaV1.PersistentClaimDefinition::claimDefId.equal(claimDefId))

    val criteria = generalCriteria.and(id)
    val result = serviceHub.vaultService.queryBy<IndyClaimDefinition>(criteria)

    return result.states.firstOrNull()
}