package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.serialization.CordaSerializable
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

object ClaimSchema

object ClaimSchemaV1 : MappedSchema(
        version = 1,
        schemaFamily = ClaimSchema.javaClass,
        mappedTypes = listOf(PersistentClaim::class.java)) {

    @Entity
    @Table(name = "claims")
    class PersistentClaim(
            @Column(name = "id")
            var id: String,
            @Column(name = "issuerDid")
            var issuerDid: String

    ) : PersistentState() {
        constructor(indyClaim: IndyClaim): this(indyClaim.id, indyClaim.issuerDid)
        constructor(): this("","")
    }
}