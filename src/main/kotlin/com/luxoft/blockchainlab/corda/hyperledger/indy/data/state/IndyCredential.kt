package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.model.Credential
import com.luxoft.blockchainlab.hyperledger.indy.model.CredentialRequest
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

open class IndyCredential(val id: String,
                          val credReq: CredentialRequest,
                          val cred: Credential,
                          val issuerDid: String,
                          override val participants: List<AbstractParty>): LinearState, QueryableState {

    override val linearId: UniqueIdentifier = UniqueIdentifier()

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is ClaimSchemaV1 -> ClaimSchemaV1.PersistentClaim(this)
            else ->  throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }
    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(ClaimSchemaV1)
}