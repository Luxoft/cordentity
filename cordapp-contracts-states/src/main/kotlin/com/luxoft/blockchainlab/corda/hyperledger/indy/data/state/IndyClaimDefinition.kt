package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimDefinitionSchemaV1
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState


class IndyClaimDefinition(
    val schemaId: String,
    val claimDefId: String,
    val revRegId: String,
    val currentCredNumber: Int,
    val maxCredNumber: Int,
    override val participants: List<AbstractParty>
) : LinearState, QueryableState {

    companion object {
        fun upgrade(credDef: IndyClaimDefinition): IndyClaimDefinition {
            return IndyClaimDefinition(
                credDef.schemaId,
                credDef.claimDefId,
                credDef.revRegId,
                credDef.currentCredNumber + 1,
                credDef.maxCredNumber,
                credDef.participants
            )
        }
    }

    override val linearId: UniqueIdentifier = UniqueIdentifier()

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is ClaimDefinitionSchemaV1 -> ClaimDefinitionSchemaV1.PersistentClaimDefinition(this)
            else -> throw IllegalArgumentException("Unrecognised schema: $schema")
        }
    }

    override fun supportedSchemas() = listOf(ClaimDefinitionSchemaV1)

    /**
     * Returns true if this credential definition is able to hold 1 more credential
     */
    fun canProduceCredentials() = currentCredNumber < maxCredNumber
}