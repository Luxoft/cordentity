package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredential
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Entity
import javax.persistence.Table

object CredentialSchema

object CredentialSchemaV1 : MappedSchema(
    version = 1,
    schemaFamily = CredentialSchema.javaClass,
    mappedTypes = listOf(PersistentCredential::class.java)
) {

    @Entity
    @Table(name = "credentials")
    class PersistentCredential(
        var issuerDid: String,
        var proverDid: String

    ) : PersistentState() {
        constructor(indyCredential: IndyCredential) : this(indyCredential.issuerDid, indyCredential.proverDid)
        constructor() : this("", "")
    }
}