package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimProofSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.model.Proof
import com.luxoft.blockchainlab.hyperledger.indy.model.ProofReq
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

/**
 * A Corda record of an Indy proof [proof] issued on request [proofReq]
 * */
open class IndyClaimProof(val id: String,
                          val proofReq: ProofReq,
                          val proof: Proof,
                          override val participants: List<AbstractParty>,
                          override val linearId: UniqueIdentifier = UniqueIdentifier()): QueryableState, LinearState {

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is ClaimProofSchemaV1 -> ClaimProofSchemaV1.PersistentProof(this)
            else ->  throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(ClaimProofSchemaV1)

}