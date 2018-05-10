package com.luxoft.blockchainlab.corda.hyperledger.indy.demo.state

import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

open class SimpleStringState(val data: String,
                             val owner: AbstractParty): QueryableState {

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is SimpleStringSchema -> SimpleStringSchema.PersistentString(
                    data = data
            )
            else ->  throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(SimpleStringSchema)

    override val participants: List<AbstractParty> = listOf(owner)
}

object SimpleStringSchema : MappedSchema(
        schemaFamily = SimpleStringSchema::class.java,
        version = 1,
        mappedTypes = listOf(PersistentString::class.java)) {

    @Entity
    @Table(name = "claims")
    class PersistentString(
            @Column var data: String = ""
    ) : PersistentState()
}