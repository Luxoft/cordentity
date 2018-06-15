package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.hyperledger.indy.model.Proof
import com.luxoft.blockchainlab.hyperledger.indy.model.ProofReq
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty

open class IndyClaimProof(val id: String,
                          val proofReq: ProofReq,
                          val proof: Proof,
                          override val participants: List<AbstractParty>,
                          override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState