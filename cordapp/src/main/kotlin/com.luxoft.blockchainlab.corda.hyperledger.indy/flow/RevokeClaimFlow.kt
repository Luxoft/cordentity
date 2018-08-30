package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.ClaimSchemaV1
import net.corda.core.node.services.vault.Builder.equal
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import net.corda.core.flows.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria

/**
 * Flow to revoke previously issued claim
 */
object RevokeClaimFlow {

    /**
     * @param revRegId      Claim's revocation registry definition id
     * @param credRevId     Claim's revocation registry index
     */
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
            private val claimId: String
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                // query vault for claim with id = claimid
                val claim = getIndyClaimState(claimId)?.state?.data
                        ?: throw RuntimeException("No such claim in vault")

                val revRegId = claim.claimInfo.claim.revRegId!!
                val credRevId = claim.claimInfo.credRevocId!!

                // revoke that claim
                indyUser().revokeClaim(revRegId, credRevId)

            } catch (ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }

    @InitiatedBy(Issuer::class)
    open class Prover(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        override fun call() {
        }
    }
}