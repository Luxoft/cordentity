package com.luxoft.blockchainlab.corda.hyperledger.indy.data

import net.corda.core.serialization.SerializationWhitelist

class IndySerializationWhitelist : SerializationWhitelist {

    override val whitelist: List<Class<*>>
        get() = listOf(
                com.luxoft.blockchainlab.hyperledger.indy.model.Did::class.java,
                com.luxoft.blockchainlab.hyperledger.indy.model.ClaimOffer::class.java,
                com.luxoft.blockchainlab.hyperledger.indy.model.ClaimReq::class.java,
                com.luxoft.blockchainlab.hyperledger.indy.model.Claim::class.java,
                com.luxoft.blockchainlab.hyperledger.indy.model.ProofReq::class.java,
                com.luxoft.blockchainlab.hyperledger.indy.model.Proof::class.java,
                com.luxoft.blockchainlab.hyperledger.indy.IndyUser.SchemaDetails::class.java
        )
}