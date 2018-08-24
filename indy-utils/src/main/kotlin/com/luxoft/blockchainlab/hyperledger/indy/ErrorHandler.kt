package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import org.hyperledger.indy.sdk.ledger.LedgerInvalidTransactionException
import org.hyperledger.indy.sdk.ledger.LedgerResults
import java.util.concurrent.CompletableFuture

open class IndyWrapperException(msg: String) : IllegalArgumentException(msg)

class ArtifactDoesntExist(msg: String = "") : IndyWrapperException("Artifact " + msg + " doesn't exist on the public ledger")
class ArtifactRequestFailed(msg: String) : IndyWrapperException("Request to public Indy ledger has failed: " + msg)

enum class Status { REJECT, REPLY }
data class IndyOpCode(val op: Status, val result: Any?)

fun IndyUser.errorHandler(execResult: String) {
    SerializationUtils.jSONToAny<IndyOpCode>(execResult)
            ?: throw ArtifactRequestFailed("Unable to extract op-code from the response")
}

fun IndyUser.errorHandler(execResult: String,
                          indyParser: ((msg: String) -> CompletableFuture<LedgerResults.ParseResponseResult>)): String {
        try {
            SerializationUtils.jSONToAny<IndyOpCode>(execResult)
                    ?: throw ArtifactRequestFailed("Unable to extract op-code from the response")

            return indyParser(execResult).get().objectJson!!

        } catch (e: IllegalArgumentException) {
            logger.info("Can not extract op-code from the response ", e)
            throw ArtifactRequestFailed("Unknown command received: " + e.message)

        } catch (e: Exception) {
            logger.info("Indy parsing has failed", e)
            if(e.cause is LedgerInvalidTransactionException) throw ArtifactDoesntExist()
            throw ArtifactRequestFailed("Can not parse the response: " + e.message)
        }
}