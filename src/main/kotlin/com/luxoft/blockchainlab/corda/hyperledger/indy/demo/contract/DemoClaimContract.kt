package com.luxoft.blockchainlab.corda.hyperledger.indy.demo.contract

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimProof
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaim
import com.luxoft.blockchainlab.corda.hyperledger.indy.demo.state.SimpleStringState
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

class DemoClaimContract : Contract {


    override fun verify(tx: LedgerTransaction) {

        tx.commands.select(Commands.Verify::class.java).forEach { command ->

            requireThat {

                val claimStates = tx.outputsOfType<IndyClaimProof>()

                claimStates.forEach( {claimProof ->
                    "All of the participants must be signers." using (command.signers.containsAll(claimProof.participants.map { it.owningKey }))
                    "IndyClaim should be verified." using (IndyUser.verifyProof(claimProof.proofReq, claimProof.proof))

                    // I got lucky
                    assert(tx.outputsOfType<SimpleStringState>().filter { it.data == "moi-moi-moi" }.size == 1)
                    // I got drunk
                    assert(tx.outputsOfType<SimpleStringState>().filter { it.data == "gulp" }.size == 1)
                    // No strings attached
                    assert(tx.outputsOfType<SimpleStringState>().size == 2)

                })
            }
        }

        tx.commands.select(Commands.Issue::class.java).forEach { command ->

            requireThat {

                val claimStates = tx.outputsOfType<IndyClaim>()

                claimStates.forEach( {claim ->
                    "All of the participants must be signers." using (command.signers.containsAll(claim.participants.map { it.owningKey }))
                })
            }
        }
    }

    interface Commands : CommandData {
        class Verify : Commands
        class Issue  : Commands
    }
}