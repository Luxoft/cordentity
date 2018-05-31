package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.model.Claim
import com.luxoft.blockchainlab.hyperledger.indy.model.ClaimOffer
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.unwrap

object IssueClaimFlow {

    @CordaSerializable
    data class IndyClaimOfferRequest(val schemaKey: String = "", val did: String = "")

    @CordaSerializable
    data class IndyClaimRequest(val claimRequest: String = "", val claimProposal: String = "")

    @InitiatingFlow
    @StartableByRPC
    open class Prover(private val schemaDetails: IndyUser.SchemaDetails,
                      private val claimProposal: String = "",
                      private var masterSecret: String,
                      private val authority: String) : FlowLogic<Claim>() {

        @Suspendable
        override fun call(): Claim {
            try {
                val otherSide: Party = serviceHub.identityService.partiesFromName(authority, true).single()
                val flowSession: FlowSession = initiateFlow(otherSide)

                val sessionDid = subFlow(CreatePairwiseFlow.Prover(authority))

                val indyClaimOfferReq = IndyClaimOfferRequest(schemaDetails.schemaKey, sessionDid)
                val indyClaimReq = flowSession.sendAndReceive<ClaimOffer>(indyClaimOfferReq).unwrap { indyClaimOffer ->
                    indyClaimOffer.also { indyUser().receiveClaimOffer(indyClaimOffer) }.let { indyClaimOffer ->
                        val indyClaimReq = indyUser().createClaimReq(schemaDetails, indyClaimOffer.issuerDid, sessionDid, masterSecret)
                        IndyClaimRequest(indyClaimReq.json, claimProposal)
                    }
                }

                return flowSession.sendAndReceive<Claim>(indyClaimReq).unwrap { indyClaim ->
                    indyClaim.also { indyUser().receiveClaim(indyClaim) }
                }

            } catch (ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }

    @InitiatedBy(Prover::class)
    open class Issuer (private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {

            try {
                val indyClaimOffer = flowSession.receive<IndyClaimOfferRequest>().unwrap{ claimOffer ->
                    claimOffer.let {
                        indyUser().createClaimOffer(claimOffer.did, IndyUser.SchemaDetails(claimOffer.schemaKey))
                    }
                }

                val indyClaim = flowSession.sendAndReceive<IndyClaimRequest>(indyClaimOffer).unwrap { claimReq ->
                    claimReq.also { verifyClaimAttributeValues(claimReq) }.let { claimReq ->
                        indyUser().issueClaim(claimReq.claimRequest, claimReq.claimProposal)
                    }
                }

                flowSession.send(indyClaim)

            } catch(ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }
}