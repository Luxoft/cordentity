package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import org.hyperledger.indy.sdk.ledger.LedgerInvalidTransactionException
import org.hyperledger.indy.sdk.ledger.LedgerResults
import java.util.concurrent.CompletableFuture

class ErrorHandler(execResult: String, indyParser: ((msg: String) -> CompletableFuture<LedgerResults.ParseResponseResult>)? = null) {

    data class IndyOpCode(val op: String, val result: Any?)

    var result: LedgerResults.ParseResponseResult? = null

    val status = try {

        val _status = Status.valueOf((SerializationUtils.jSONToAny<IndyOpCode>(execResult)
                ?: throw IllegalArgumentException("Unable to extract op-code from response")).op)

        if(_status == Status.REPLY && indyParser != null) {
            try {
                result = indyParser(execResult).get()
                _status
            } catch (e: Exception) {
                if(e.cause is LedgerInvalidTransactionException) Status.EMPTY
                else Status.FAILED
            }
        } else _status

    } catch (e: IllegalArgumentException) { Status.UNKNOWN }

    enum class Status {
        REJECT, REPLY, EMPTY, FAILED, UNKNOWN
    }

    fun isFailed(): Boolean =
        when(status) {
            Status.REJECT -> true
            Status.FAILED -> true
            Status.REPLY -> false
            Status.EMPTY -> false
            else -> true
        }

    fun getReason(): String {
        return ""
    }

    override fun toString(): String = getReason()
}