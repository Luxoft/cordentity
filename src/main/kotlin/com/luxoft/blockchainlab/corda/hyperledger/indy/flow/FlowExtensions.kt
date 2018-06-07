package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.model.ClaimReq
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party


// Extension methods to reduce boilerplate code in Indy flows
fun FlowLogic<Any>.whoIs(x509: CordaX500Name): Party {
    return serviceHub.identityService.wellKnownPartyFromX500Name(x509)!!
}

fun FlowLogic<Any>.whoIsNotary(): Party {
    return serviceHub.networkMapCache.notaryIdentities.single()
}

fun FlowLogic<Any>.indyUser(): IndyUser {

    return serviceHub.cordaService(IndyService::class.java).indyUser
}

fun FlowLogic<Any>.verifyClaimAttributeValues(claimRequest: ClaimReq): Boolean {

    return serviceHub.cordaService(IndyService::class.java).claimAttributeValuesChecker.verifyRequestedClaimAttributes(claimRequest)
}