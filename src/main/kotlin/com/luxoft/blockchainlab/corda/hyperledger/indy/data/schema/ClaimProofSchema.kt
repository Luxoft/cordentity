package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.serialization.CordaSerializable
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table


object ClaimProofSchema

object ClaimProofSchemaV1 : MappedSchema(
        version = 1,
        schemaFamily = ClaimProofSchema.javaClass,
        mappedTypes = listOf(PersistentClaim::class.java)) {

    @Entity
    @Table(name = "proofs")
    class PersistentClaim(
            @Column(name = "id")
            val id: String
    ) : PersistentState()
}