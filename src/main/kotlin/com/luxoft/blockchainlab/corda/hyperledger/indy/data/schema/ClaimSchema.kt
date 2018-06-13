package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

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

            @Column(name = "claimReq", length = 200000)
            var claimReq: String,

            @Column(name = "claim", length = 200000)
            var claim: String,

            // IssuerDid and Schema are contained in plainText but desirable for better search
            @Column
            var issuerDid: String

    ) : PersistentState()
}