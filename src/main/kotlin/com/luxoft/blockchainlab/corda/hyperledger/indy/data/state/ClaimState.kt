package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimProofSchemaV1
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.model.Claim
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

open class ClaimState(val claim: Claim,
                      owner: AbstractParty): QueryableState {

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is ClaimSchemaV1 -> ClaimSchemaV1.PersistentClaim(
                    claimPlainText = claim.json,
                    issuerDid = claim.issuerDid,
                    schemaKey = claim.schemaKey
            )
            else ->  throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(ClaimProofSchemaV1)

    override val participants: List<AbstractParty> = listOf(owner)
}