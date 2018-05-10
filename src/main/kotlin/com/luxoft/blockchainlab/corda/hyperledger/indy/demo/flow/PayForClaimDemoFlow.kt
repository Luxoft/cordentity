//package com.luxoft.blockchainlab.corda.hyperledger.indy.demo.flow
//
//import co.paralleluniverse.fibers.Suspendable
//import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
//import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
//import com.luxoft.blockchainlab.hyperledger.indy.model.Proof
//import com.luxoft.blockchainlab.hyperledger.indy.model.ProofReq
//import net.corda.core.flows.*
//import net.corda.core.identity.Party
//import net.corda.core.utilities.unwrap
//
//object PayForClaimDemoFlow {
//
//    @InitiatingFlow
//    @StartableByRPC
//    open class Verifier (
//            private val attributes: List<IndyUser.ProofAttribute>,
//            private val predicates: List<IndyUser.ProofPredicate>,
//            private val prover: String
//    ) : FlowLogic<Boolean>() {
//
//        @Suspendable
//        override fun call(): Boolean {
//            return try {
//                val otherSide: Party = serviceHub.identityService.partiesFromName(prover, true).single()
//                val flowSession: FlowSession = initiateFlow(otherSide)
//
//                val proofRequest = indyUser().createProofReq(attributes, predicates)
//
//                flowSession.sendAndReceive<Proof>(proofRequest).unwrap { proof -> IndyUser.verifyProof(proofRequest, proof) }
//
//            } catch (e: Exception) {
//                e.printStackTrace()
//                false
//            }
//        }
//    }
//
//    @InitiatedBy(VerifyClaimInContractDemoFlow.Verifier::class)
//    open class Prover (private val flowSession: FlowSession) : FlowLogic<Unit>() {
//        @Suspendable
//        override fun call() {
//            try {
//                flowSession.receive(ProofReq::class.java).unwrap { indyProofReq ->
//                    // TODO: Master Secret should be received from the outside
//                    val masterSecret = indyUser().masterSecret
//                    flowSession.send(indyUser().createProof(indyProofReq, masterSecret))
//                }
//
//            } catch (e: Exception) {
//                e.printStackTrace()
//                throw FlowException(e)
//            }
//        }
//    }
//}