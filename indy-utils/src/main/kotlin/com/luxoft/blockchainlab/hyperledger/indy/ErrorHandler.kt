package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import org.hyperledger.indy.sdk.IndyException
import org.hyperledger.indy.sdk.ledger.LedgerInvalidTransactionException
import org.hyperledger.indy.sdk.ledger.LedgerResults
import java.util.concurrent.CompletableFuture

open class IndyWrapperException(msg: String) : IllegalArgumentException(msg)

class ArtifactDoesntExist(msg: String = "") : IndyWrapperException("Artifact " + msg + " doesn't exist on the public ledger")
class ArtifactRequestFailed(msg: String) : IndyWrapperException("Request to public Indy ledger has failed: " + msg)

enum class Status { REJECT, REPLY }
data class IndyOpCode(val op: Status, val result: Any?)

inline fun IndyUser.errorHandler(execResult: String) {
    val res = try {
         SerializationUtils.jSONToAny<IndyOpCode>(execResult)
                ?: throw ArtifactRequestFailed("Unable to extract op-code from the response")
    } catch (e: IllegalArgumentException) {
        logger.info("Can not extract op-code from the response ", e)
        throw ArtifactRequestFailed("Unknown command received: " + e.message)
    }

    when (res.op) {
        Status.REJECT -> throw ArtifactRequestFailed("Request has been rejected: ${res.result}")
        Status.REPLY -> logger.info("Request successfully completed: ${res.result}")
    }
}

inline fun <reified T: Any>IndyUser.extractResult(execResult: String,
                          indyParser: ((msg: String) -> CompletableFuture<LedgerResults.ParseResponseResult>)): T {
        try {
            errorHandler(execResult)

            val payload = indyParser(execResult).get()
            val output = SerializationUtils.jSONToAny(payload.objectJson!!, T::class.java)
                    ?: throw RuntimeException("Unable to parse payload from json")

            logger.info("Payload successfully parsed ${payload.id}:${payload.objectJson}")
            return output

        } catch (e: Exception) {
            logger.info("Indy parsing has failed", e)
            if(e.cause is LedgerInvalidTransactionException) throw ArtifactDoesntExist()
            throw ArtifactRequestFailed("Can not parse the response: " + e.message)
        }
}