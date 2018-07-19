package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.unwrap

object AssignPermissionsFlow {

    @CordaSerializable
    data class IndyPermissionsRequest(val did: String = "",
                                      val verkey: String = "",
                                      val alias: String?,
                                      val role: String?)

    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
            private val alias: String? = null,
            private val role: String? = null,
            private val authority: CordaX500Name
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val otherSide: Party = whoIs(authority)
                val flowSession: FlowSession = initiateFlow(otherSide)

                flowSession.send(IndyPermissionsRequest(indyUser().did, indyUser().verkey, role, alias))

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }

    @InitiatedBy(Issuer::class)
    open class Authority (private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                flowSession.receive(IndyPermissionsRequest::class.java).unwrap { indyPermissions ->
                    indyUser().setPermissionsFor(IndyUser.IdentityDetails(
                            indyPermissions.did,
                            indyPermissions.verkey,
                            indyPermissions.role,
                            indyPermissions.alias))
                }

            } catch(ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }

    }

}