package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.CredentialDefinitionSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.CredentialSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.IndySchemaSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredential
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialDefinition
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndySchema
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.SchemaId
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.vaultQueryBy
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

/**
 * This method is used to get indy credential state from vault
 *
 * @param proverDid       Did of the receiver
 *
 * @return                  corda state of indy credential or null if none exists
 */
fun FlowLogic<Any>.getIndyCredentialsByProver(proverDid: String): List<StateAndRef<IndyCredential>> {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val existingId =
        QueryCriteria.VaultCustomQueryCriteria(CredentialSchemaV1.PersistentCredential::proverDid.equal(proverDid))

    val criteria = generalCriteria.and(existingId)
    val result = serviceHub.vaultService.queryBy<IndyCredential>(criteria)

    return result.states
}

/**
 * This method is used to get indy credential state from vault
 *
 * @param issuerDid         Did of the issuer
 *
 * @return                  corda state of indy credential or null if none exists
 */
fun FlowLogic<Any>.getIndyCredentialsByIssuer(issuerDid: String): List<StateAndRef<IndyCredential>> {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val existingId =
        QueryCriteria.VaultCustomQueryCriteria(CredentialSchemaV1.PersistentCredential::issuerDid.equal(issuerDid))

    val criteria = generalCriteria.and(existingId)
    val result = serviceHub.vaultService.queryBy<IndyCredential>(criteria)

    return result.states
}

fun CordaRPCOps.getIndyCredentialsByIssuer(issuerDid: String): List<StateAndRef<IndyCredential>> {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val existingId =
        QueryCriteria.VaultCustomQueryCriteria(CredentialSchemaV1.PersistentCredential::issuerDid.equal(issuerDid))

    val criteria = generalCriteria.and(existingId)
    val result = vaultQueryBy<IndyCredential>(criteria)

    return result.states
}

fun CordaRPCOps.getIndyCredentialsByProver(proverDid: String): List<StateAndRef<IndyCredential>> {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val existingId =
        QueryCriteria.VaultCustomQueryCriteria(CredentialSchemaV1.PersistentCredential::proverDid.equal(proverDid))

    val criteria = generalCriteria.and(existingId)
    val result = vaultQueryBy<IndyCredential>(criteria)

    return result.states
}

private fun FlowLogic<Any>.getUnconsumedCredentialDefinitionByCriteria(
    criteria: QueryCriteria.VaultCustomQueryCriteria<CredentialDefinitionSchemaV1.PersistentCredentialDefinition>
): StateAndRef<IndyCredentialDefinition>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

    val criteria = generalCriteria.and(criteria)
    val result = serviceHub.vaultService.queryBy<IndyCredentialDefinition>(criteria)

    return result.states.firstOrNull()
}

fun FlowLogic<Any>.getCredentialDefinitionById(credentialDefinitionId: CredentialDefinitionId): StateAndRef<IndyCredentialDefinition>? {
    return getUnconsumedCredentialDefinitionByCriteria(
        QueryCriteria.VaultCustomQueryCriteria(
            CredentialDefinitionSchemaV1.PersistentCredentialDefinition::credentialDefId.equal(credentialDefinitionId.toString())
        )
    )
}

fun FlowLogic<Any>.getCredentialDefinitionBySchemaId(schemaId: SchemaId): StateAndRef<IndyCredentialDefinition>? {
    return getUnconsumedCredentialDefinitionByCriteria(
        QueryCriteria.VaultCustomQueryCriteria(
            CredentialDefinitionSchemaV1.PersistentCredentialDefinition::schemaId.equal(schemaId.toString())
        )
    )
}

fun FlowLogic<Any>.getSchemaById(schemaId: SchemaId): StateAndRef<IndySchema>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val id = QueryCriteria.VaultCustomQueryCriteria(IndySchemaSchemaV1.PersistentSchema::id.equal(schemaId.toString()))

    val criteria = generalCriteria.and(id)
    val result = serviceHub.vaultService.queryBy<IndySchema>(criteria)

    return result.states.firstOrNull()
}