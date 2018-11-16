package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.CredentialSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.CredentialInfo
import com.luxoft.blockchainlab.hyperledger.indy.CredentialRequestInfo
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

/**
 * A Corda record of an Indy Credential issued on request [CredentialRequestInfo]
 *
 * @param credentialRequestInfo     indy credential request
 * @param credentialInfo            indy credential
 * @param issuerDid                 did of an entity issued credential
 * @param proverDid                 did of an entity received credential
 * @param participants              corda participants
 */
open class IndyCredential(
    val credentialRequestInfo: CredentialRequestInfo,
    val credentialInfo: CredentialInfo,
    val issuerDid: String,
    val proverDid: String,
    override val participants: List<AbstractParty>
) : LinearState, QueryableState {

    override val linearId: UniqueIdentifier = UniqueIdentifier()

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is CredentialSchemaV1 -> CredentialSchemaV1.PersistentCredential(this)
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(CredentialSchemaV1)
}