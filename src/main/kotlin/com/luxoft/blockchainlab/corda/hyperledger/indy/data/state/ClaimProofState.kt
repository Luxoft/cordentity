package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimProofSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.model.Proof
import com.luxoft.blockchainlab.hyperledger.indy.model.ProofReq
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

open class ClaimProofState(val proofReq: ProofReq,
                           val proof: Proof,
                           override val participants: List<AbstractParty>): QueryableState {

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is ClaimProofSchemaV1 -> ClaimProofSchemaV1.ClaimProofRecord(
                    proofReq = proofReq.json,
                    proof = proof.json
            )
            else ->  throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(ClaimProofSchemaV1)
}