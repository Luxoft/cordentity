package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimProof
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.contracts.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

/**
 * Example contract with claim verification. Use responsibly
 * as Corda will probably remove JNI support (i.e. Libindy calls)
 * in near future in deterministic JVM
 */
class ClaimChecker : Contract {

    @CordaSerializable
    data class ExpectedAttr(val name: String, val value: String)

    override fun verify(tx: LedgerTransaction) {
        val incomingCommand = tx.commands.requireSingleCommand<Commands>()
        val signers = incomingCommand.signers.toSet()

        val command = incomingCommand.value

        when(command) {
            is Commands.Verify -> verification(tx, signers, command.expectedAttrs)
            is Commands.Issue -> creation(tx, signers)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    private fun verification(tx: LedgerTransaction, signers: Set<PublicKey>, expectedAttrs: List<ExpectedAttr>) = requireThat {

        "No inputs should be consumed when creating the proof." using (tx.inputStates.isEmpty())
        "Only one Proof should be created per verification session." using (tx.outputStates.size == 1)

        val indyProof = tx.outputsOfType<IndyClaimProof>().singleOrNull()
                ?: throw IllegalArgumentException("Invalid type of output")

        "All of the participants must be signers." using (signers.containsAll(indyProof.participants.map { it.owningKey }))
        "IndyClaim should be verified." using (IndyUser.verifyProof(indyProof.proofReq, indyProof.proof))

        expectedAttrs.forEach {
            "Proof provided for invalid value." using indyProof.proof.isAttributeExist(it.value)
        }

    }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // Probably can check something here too...
    }

    interface Commands : CommandData {
        data class Verify(val expectedAttrs: List<ExpectedAttr>) : Commands
        class Issue: Commands
    }
}