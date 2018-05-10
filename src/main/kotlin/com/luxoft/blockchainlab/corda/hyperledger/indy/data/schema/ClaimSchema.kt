package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.serialization.CordaSerializable
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

object ClaimSchema

object ClaimSchemaV1 : MappedSchema(
        schemaFamily = ClaimSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentClaim::class.java)) {

    @Entity
    @Table(name = "claims")
    class PersistentClaim(
            @Column var claimPlainText: String = "",

            // IssuerDid and Schema are contained in plainText but desirable for better search
            @Column var issuerDid: String = "",
            @Column var schemaKey: String = ""
    ) : PersistentState()
}