package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction

class ClaimVerification : Contract {

    @CordaSerializable
    data class ExpectedAttr<N, V:Any>(val name: N, val value: V)

    override fun verify(tx: LedgerTransaction) {

    }


    interface Commands : CommandData {
        data class Verify<N, V:Any>(val expectedAttrs: List<ExpectedAttr<N, V>>) : Commands
        class Issue  : Commands
    }
}