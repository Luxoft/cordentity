package com.luxoft.blockchainlab.hyperledger.indy.payments

import org.hyperledger.indy.sdk.wallet.Wallet


data class TokenInfo(val name: String, val abbreviation: String)
data class AddressConfig(val seed: String)


fun lol() {

}


interface IndyPayments {
    fun createPaymentAddress(wallet: Wallet, tokenInfo: TokenInfo, addressConfig: AddressConfig)
    fun listPaymentAddresses(wallet: Wallet)
    fun mintToken(wallet: Wallet)
}