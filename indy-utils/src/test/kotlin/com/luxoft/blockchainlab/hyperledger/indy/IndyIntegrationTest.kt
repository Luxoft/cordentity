package com.luxoft.blockchainlab.hyperledger.indy

import org.hyperledger.indy.sdk.crypto.CryptoJSONParameters
import org.hyperledger.indy.sdk.did.DidJSONParameters
import org.hyperledger.indy.sdk.pool.Pool
import org.junit.Rule
import org.junit.rules.ExpectedException
import org.junit.rules.Timeout
import java.util.*
import java.util.concurrent.TimeUnit


open class IndyIntegrationTest {
    protected var SIGNATURE = byteArrayOf(20, -65, 100, -43, 101, 12, -59, -58, -53, 49, 89, -36, -51, -64, -32, -35, 97, 77, -36, -66, 90, 60, -114, 23, 16, -16, -67, -127, 45, -108, -11, 8, 102, 95, 95, -7, 100, 89, 41, -29, -43, 25, 100, 1, -24, -68, -11, -21, -70, 21, 52, -80, -20, 11, 99, 70, -101, -97, 89, -41, -59, -17, -118, 5)
    protected var ENCRYPTED_MESSAGE = byteArrayOf(-105, 30, 89, 75, 76, 28, -59, -45, 105, -46, 20, 124, -85, -13, 109, 29, -88, -82, -8, -6, -50, -84, -53, -48, -49, 56, 124, 114, 82, 126, 74, 99, -72, -78, -117, 96, 60, 119, 50, -40, 121, 21, 57, -68, 89)
    protected var NONCE = byteArrayOf(-14, 102, -41, -57, 1, 4, 75, -46, -91, 87, 14, 41, -39, 48, 42, -126, -121, 84, -58, 59, -27, 51, -32, -23)
    protected var DEFAULT_CRED_DEF_CONFIG = "{\"support_revocation\":false}"
    protected var TAG = "tag1"
    protected var GVT_SCHEMA_NAME = "gvt"
    protected var XYZ_SCHEMA_NAME = "xyz"
    protected var SCHEMA_VERSION = "1.0"
    protected var GVT_SCHEMA_ATTRIBUTES = listOf("name", "age", "sex", "height")
    protected var XYZ_SCHEMA_ATTRIBUTES = "[\"status\", \"period\"]"
    protected var REVOC_REG_TYPE = "CL_ACCUM"
    protected var SIGNATURE_TYPE = "CL"
//    protected var TAILS_WRITER_CONFIG = JSONObject(String.format("{\"base_dir\":\"%s\", \"uri_pattern\":\"\"}", getIndyHomePath("tails")).replace('\\', '/')).toString()
    protected var REV_CRED_DEF_CONFIG = "{\"support_revocation\":true}"
    protected var GVT_CRED_VALUES = "{\n" +
            "        \"sex\": {\"raw\": \"male\", \"encoded\": \"5944657099558967239210949258394887428692050081607692519917050\"},\n" +
            "        \"name\": {\"raw\": \"Alex\", \"encoded\": \"1139481716457488690172217916278103335\"},\n" +
            "        \"height\": {\"raw\": \"175\", \"encoded\": \"175\"},\n" +
            "        \"age\": {\"raw\": \"28\", \"encoded\": \"28\"}\n" +
            "    }"
    protected var CREDENTIALS = "{\"key\": \"key\"}"
    protected var PROTOCOL_VERSION = 2


    protected val TRUSTEE_SEED = "000000000000000000000000Trustee1"
    protected val MY1_SEED = "00000000000000000000000000000My1"
    protected val MY2_SEED = "00000000000000000000000000000My2"
    protected val VERKEY = "CnEDk9HrMnmiHXEV1WFgbVCRteYnPqsJwrTdcZaNhFVW"
    protected val VERKEY_MY1 = "GjZWsBLgZCR18aL468JAT7w9CZRiBnpxUPPgyQxh4voa"
    protected val VERKEY_MY2 = "kqa2HyagzfMAq42H5f9u3UMwnSBPQx2QfrSyXbUPxMn"
    protected val VERKEY_TRUSTEE = "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"
    protected val INVALID_VERKEY = "CnEDk___MnmiHXEV1WFgbV___eYnPqs___TdcZaNhFVW"
    protected val DID = "8wZcEriaNLNKtteJvx7f8i"
    protected val DID_MY1 = "VsKV7grR1BUE29mG2Fm2kX"
    protected val DID_MY2 = "2PRyVHmkXQnQzJQKxHxnXC"
    protected val DID_TRUSTEE = "V4SGRU86Z58d6TV7PBUe6f"
    protected val INVALID_DID = "invalid_base58string"
    protected val IDENTITY_JSON_TEMPLATE = "{\"did\":\"%s\",\"verkey\":\"%s\"}"
    protected val MESSAGE = "{\"reqId\":1496822211362017764}".toByteArray()
    protected val SCHEMA_DATA = "{\"id\":\"id\", \"name\":\"gvt\",\"version\":\"1.0\",\"attrNames\":[\"name\"],\"ver\":\"1.0\"}"
    protected val POOL = "Pool1"
    protected val WALLET = "Wallet1"
    protected val TYPE = "default"
    protected val METADATA = "some metadata"
    protected val ENDPOINT = "127.0.0.1:9700"
    protected val CRYPTO_TYPE = "ed25519"


    protected var openedPools = HashSet<Pool>()

    /*@Before
    @Throws(Exception::class)
    fun setUp() {
        InitHelper.init()
        StorageUtils.cleanupStorage()
        Pool.setProtocolVersion(PROTOCOL_VERSION).get()
        //		if (! isWalletRegistered) { TODO:FIXME
        //			Wallet.registerWalletType("inmem", new InMemWalletType()).get();
        //		}
        isWalletRegistered = true
    }
*/
   /* @After
    @Throws(IOException::class)
    fun tearDown() {
        openedPools.forEach { pool ->
            try {
                pool.closePoolLedger().get()
            } catch (ignore: IndyException) {
            } catch (ignore: InterruptedException) {
            } catch (ignore: ExecutionException) {
            }
        }
        openedPools.clear()
        StorageUtils.cleanupStorage()
    }*/


    protected val TRUSTEE_IDENTITY_JSON = DidJSONParameters.CreateAndStoreMyDidJSONParameter(null, TRUSTEE_SEED, null, null).toJson()

    protected val MY1_IDENTITY_JSON = DidJSONParameters.CreateAndStoreMyDidJSONParameter(null, MY1_SEED, null, null).toJson()

    protected val MY1_IDENTITY_KEY_JSON = CryptoJSONParameters.CreateKeyJSONParameter(MY1_SEED, null).toJson()

//        protected val EXPORT_KEY = "export_key"
//        protected val EXPORT_PATH = getTmpPath("export_wallet")
//        protected val EXPORT_CONFIG_JSON = JSONObject()
//                .put("key", EXPORT_KEY)
//                .put("path", EXPORT_PATH)
//                .toString()

}
