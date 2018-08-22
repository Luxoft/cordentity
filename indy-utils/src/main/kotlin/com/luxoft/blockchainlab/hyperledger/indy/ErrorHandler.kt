package com.luxoft.blockchainlab.hyperledger.indy

import org.hyperledger.indy.sdk.ledger.LedgerInvalidTransactionException
import org.hyperledger.indy.sdk.ledger.LedgerResults
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

class ErrorHandler(val execResult: String, indyParser: ((msg: String) -> CompletableFuture<LedgerResults.ParseResponseResult>)? = null) {

    var result: LedgerResults.ParseResponseResult? = null

    val status = try {

        val _status = Status.valueOf(JSONObject(execResult).get("op").toString().toUpperCase())

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