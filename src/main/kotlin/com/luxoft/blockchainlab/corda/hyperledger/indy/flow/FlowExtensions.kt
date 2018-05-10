package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import net.corda.core.flows.FlowLogic


// Extension methods to reduce boilerplate code in Indy flows

fun FlowLogic<Any>.indyUser(): IndyUser {

    return serviceHub.cordaService(IndyService::class.java).indyUser
}

fun FlowLogic<Any>.verifyClaimAttributeValues(claimRequest: IssueClaimFlow.IndyClaimRequest): Boolean {

    return serviceHub.cordaService(IndyService::class.java).claimAttributeValuesChecker.verifyRequestedClaimAttributes(claimRequest)
}