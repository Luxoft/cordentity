package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimDefinition
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity


object ClaimDefinitionSchema

object ClaimDefinitionSchemaV1 : MappedSchema(
    version = 1,
    schemaFamily = ClaimDefinitionSchema.javaClass,
    mappedTypes = listOf(PersistentClaimDefinition::class.java)
) {
    @Entity
    data class PersistentClaimDefinition(
        @Column(name = "id") val claimDefId: String = "",
        val schemaId: String = "",
        val revRegId: String = ""

    ) : PersistentState() {
        constructor(claimDef: IndyClaimDefinition) : this(claimDef.claimDefId, claimDef.schemaId, claimDef.revRegId)
    }
}