package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey


class ClaimMetadataChecker : Contract {
    override fun verify(tx: LedgerTransaction) {
        val incomingCommand = tx.commands.requireSingleCommand<Command>()
        val signers = incomingCommand.signers.toSet()

        val command = incomingCommand.value

        when (command) {
            is Command.Create -> checkCreate(tx, signers)
            is Command.Use -> checkUse(tx, signers)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    interface Command : CommandData {
        class Create: Command
        class Use: Command
    }

    private fun checkUse(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: check something
    }

    private fun checkCreate(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: check something
    }
}