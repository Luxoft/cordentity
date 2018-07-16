package com.luxoft.blockchainlab.corda.hyperledger.indy.service

import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.model.ClaimReq
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import com.natpryce.konfig.*
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import org.hyperledger.indy.sdk.did.DidJSONParameters
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletExistsException
import org.slf4j.LoggerFactory
import java.io.File

@CordaService
class IndyService(services: AppServiceHub) : SingletonSerializeAsToken() {

    private val poolName = "default_pool"
    private val credentials = """{"key": "key"}"""

    private val config = TestConfigurationsProvider.config(services.myInfo.legalIdentities.first().name.organisation) ?: EmptyConfiguration
            .ifNot(ConfigurationProperties.fromFileOrNull(File("indyconfig", "indy.properties")), indyuser) // file with common name if you go for file-based config
            .ifNot(ConfigurationProperties.fromFileOrNull(File("indyconfig", "${services.myInfo.legalIdentities.first().name.organisation}.indy.properties")), indyuser)  //  file with node-specific name
            .ifNot(EnvironmentVariables(), indyuser) // Good for docker-compose, ansible-playbook or similar

    private val logger = LoggerFactory.getLogger(IndyService::class.java.name)

    val indyUser: IndyUser

    val claimAttributeValuesChecker: ClaimAttributeValuesChecker = object : ClaimAttributeValuesChecker {}

    init {
        val walletName = try { config[indyuser.walletName] } catch (e: Exception) { services.myInfo.legalIdentities.first().name.organisation }

        try {
            Wallet.createWallet(poolName, walletName, "default", null, credentials).get()
        } catch (ex: Exception) {
            if (getRootCause(ex) !is WalletExistsException) throw ex else logger.debug("Wallet already exists")
        }

        val wallet = Wallet.openWallet(walletName, null, credentials).get()

        indyUser = if(config.getOrNull(indyuser.role)?.compareTo("trustee", true) == 0) {
            val didConfig = DidJSONParameters.CreateAndStoreMyDidJSONParameter(
                    config[indyuser.did], config[indyuser.seed], null, null).toJson()

            IndyUser(wallet, config[indyuser.did], didConfig)
        } else {
            IndyUser(wallet)
        }
    }


    interface ClaimAttributeValuesChecker {

        /**
         * Checks if requested attributes and its values matches with Issuer's view of world
         * e.g. age of counterparty is really 21 in your trusted database
         * @return true if attribute values are ok and Issuer can sign claim
         */
        fun verifyRequestedClaimAttributes(claimRequest: ClaimReq): Boolean {
            // do nothing in default implementation
            return true
        }
    }


    @Suppress("ClassName")
    object indyuser : PropertyGroup() {
        val role   by stringType
        val did    by stringType
        val seed   by stringType
        val walletName by stringType
    }
}