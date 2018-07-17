package com.luxoft.blockchainlab.hyperledger.indy.model

import com.luxoft.blockchainlab.hyperledger.indy.utils.*
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults
import org.hyperledger.indy.sdk.ledger.LedgerResults
import org.json.JSONObject


abstract class JsonDataObject(val json: JSONObject) {
    override fun equals(other: Any?): Boolean =
            other != null
                    && this.javaClass == other.javaClass
                    && other is JsonDataObject
                    && json.similar(other.json)

    override fun hashCode() = javaClass.hashCode() * 37 + json.hashCode()

    override fun toString() = "${javaClass.simpleName}($json)"
}



class Did(json: JSONObject) : JsonDataObject(json) {
    constructor(jsonStr: String) : this(JSONObject(jsonStr))
}

class Pairwise(json: JSONObject) : JsonDataObject(json) {
    constructor(pairwiseJson: String) : this(JSONObject(pairwiseJson))

    val did = json.getString("my_did")
    val metadata = json.get("metadata").toString()
}

/**
 *     {
 *         "schema_id": string,
 *         "cred_def_id": string,
 *         // Fields below can depend on Cred Def type
 *         "nonce": string,
 *         "key_correctness_proof" : <key_correctness_proof>
 *     }
 **/
class ClaimOffer(json: JSONObject) : JsonDataObject(json) {
    constructor(issuerCreateCredentialOffer: String) : this(JSONObject(issuerCreateCredentialOffer))

    val schemaId = json.getString("schema_id")
    val credDefId = json.getString("cred_def_id")
}

/**
 * * credReqJson: Credential request json for creation of credential by Issuer
 *     {
 *      "prover_did" : string,
 *      "cred_def_id" : string,
 *         // Fields below can depend on Cred Def type
 *      "blinded_ms" : <blinded_master_secret>,
 *      "blinded_ms_correctness_proof" : <blinded_ms_correctness_proof>,
 *      "nonce": string
 *    }
 * credReqMetadataJson: Credential request metadata json for processing of received form Issuer credential.
 * */
class ClaimReq(json: JSONObject, val metadata: String) : JsonDataObject(json) {
    constructor(credentialRequestResult: AnoncredsResults.ProverCreateCredentialRequestResult)
            : this(JSONObject(credentialRequestResult.credentialRequestJson), credentialRequestResult.credentialRequestMetadataJson)

    val proverDid: String = json.getString("prover_did")
    val credDefId: String = json.getString("cred_def_id")
}


/**
 * credentialJson: Credential json containing signed credential values
 *     {
 *         "schema_id": string,
 *         "cred_def_id": string,
 *         "rev_reg_def_id", Optional<string>,
 *         "values": { "attr1" : {"raw": "value1", "encoded": "value1_as_int" }, ... }
 *
 *         // Fields below can depend on Cred Def type
 *         "signature": <signature>,
 *         "signature_correctness_proof": <signature_correctness_proof>
 *     }
 * */
class Claim(json: JSONObject) : JsonDataObject(json) {
    constructor(credentialJson: String) : this(JSONObject(credentialJson))

    val schemaId = json.getString("schema_id")
    val credDefId = json.getString("cred_def_id")
    val revRegDefId = json.getStringOrNull("rev_reg_def_id")

    val values = json.getJSONObject("values")

    val signature = json.get("signature").toString()
}


/**
 * {
 *     "referent": <string>,
 *     "attrs": [{"attr_name" : "attr_raw_value"}],
 *     "schema_id": string,
 *     "cred_def_id": string,
 *     "rev_reg_id": Optional<int>,
 *     "cred_rev_id": Optional<int>,
 * }
 **/
class ClaimRef(json: JSONObject) : JsonDataObject(json) {
    constructor(credential_info: String) : this(JSONObject(credential_info))

    val referentClaim: String = json.getString("referent")
    val schemaId: String = json.getString("schema_id")

    val credDefId: String = json.getString("cred_def_id")
    val revRegId: Int? = json.getIntOrNull("rev_reg_id")
    val credRevId: Int? = json.getIntOrNull("cred_rev_id")

    val attributes: Map<String, String> = json.getJSONObject("attrs").toStringMap()

}

/**
 * proof request json
 *     {
 *         "name": string,
 *         "version": string,
 *         "nonce": string,
 *         "requested_attributes": { // set of requested attributes
 *              "<attr_referent>": <attr_info>, // see below
 *              ...,
 *         },
 *         "requested_predicates": { // set of requested predicates
 *              "<predicate_referent>": <predicate_info>, // see below
 *              ...,
 *          },
 *         "non_revoked": Optional<<non_revoc_interval>>, // see below,
 *                        // If specified prover must proof non-revocation
 *                        // for date in this interval for each attribute
 *                        // (can be overridden on attribute level)
 *     }
 *
 *     where
 *
 *
 *     attr_referent: Describes requested attribute
 *     {
 *         "name": string, // attribute name, (case insensitive and ignore spaces)
 *         "restrictions": Optional<[<attr_filter>]> // see below,
 *                          // if specified, credential must satisfy to one of the given restriction.
 *         "non_revoked": Optional<<non_revoc_interval>>, // see below,
 *                        // If specified prover must proof non-revocation
 *                        // for date in this interval this attribute
 *                        // (overrides proof level interval)
 *     }
 *     predicate_referent: Describes requested attribute predicate
 *     {
 *         "name": attribute name, (case insensitive and ignore spaces)
 *         "p_type": predicate type (Currently >= only)
 *         "p_value": predicate value
 *         "restrictions": Optional<[<attr_filter>]> // see below,
 *                         // if specified, credential must satisfy to one of the given restriction.
 *         "non_revoked": Optional<<non_revoc_interval>>, // see below,
 *                        // If specified prover must proof non-revocation
 *                        // for date in this interval this attribute
 *                        // (overrides proof level interval)
 *     }
 *     non_revoc_interval: Defines non-revocation interval
 *     {
 *         "from": Optional<int>, // timestamp of interval beginning
 *         "to": Optional<int>, // timestamp of interval ending
 *     }
 *     filter:
 *     {
 *         "schema_id": string, (Optional)
 *         "schema_issuer_did": string, (Optional)
 *         "schema_name": string, (Optional)
 *         "schema_version": string, (Optional)
 *         "issuer_did": string, (Optional)
 *         "cred_def_id": string, (Optional)
 *     }
 * */
class ProofReq(json: JSONObject) : JsonDataObject(json) {
    constructor(jsonStr: String) : this(JSONObject(jsonStr))

    val attributes = json.getJSONObject("requested_attributes")
    val predicates = json.getJSONObject("requested_predicates")

    fun getPredicateValue(key: String, filter: String): String {
        return predicates.getJSONObject(key).get(filter).toString()
    }

    fun getAttributeValue(key: String, filter: String): String {
        return attributes.getJSONObject(key).get(filter).toString()
    }
}

/**
 *     {
 *         "requested_proof": {
 *             "revealed_attrs": {
 *                 "requested_attr1_id": {sub_proof_index: number, raw: string, encoded: string},
 *                 "requested_attr4_id": {sub_proof_index: number: string, encoded: string},
 *             },
 *             "unrevealed_attrs": {
 *                 "requested_attr3_id": {sub_proof_index: number}
 *             },
 *             "self_attested_attrs": {
 *                 "requested_attr2_id": self_attested_value,
 *             },
 *             "predicates": {
 *                 "requested_predicate_1_referent": {sub_proof_index: int},
 *                 "requested_predicate_2_referent": {sub_proof_index: int},
 *             }
 *         }
 *         "proof": {
 *             "proofs": [ <credential_proof>, <credential_proof>, <credential_proof> ],
 *             "aggregated_proof": <aggregated_proof>
 *         }
 *         "identifiers": [{schema_id, cred_def_id, Optional<rev_reg_id>, Optional<timestamp>}]
 *     }
 **/
class Proof(json: JSONObject, val usedSchemas: String, val usedClaimDefs: String) : JsonDataObject(json) {
    constructor(jsonStr: String,  usedSchemas: String, usedClaimDefs: String) : this(JSONObject(jsonStr), usedSchemas, usedClaimDefs)

    private val revealedAttrs = json
            .getJSONObject("requested_proof")
            .getJSONObject("revealed_attrs")
            .toObjectMap()

    fun isAttributeExist(attr: String): Boolean {
        return revealedAttrs.any { (key, value) -> value.getString("raw") == attr }
    }

    fun getAttributeValue(attr: String, proofReq: String): String? {

        val attributesMapping = ProofReq(proofReq).attributes
        val groupedByAttr = attributesMapping.keySet()
                .associateBy { attributesMapping[it as String] to it }
                .keys.map { (it.first as JSONObject)["name"] to it.second }.toMap()

        val idx = groupedByAttr[attr]

        return revealedAttrs[idx]!!.getString("raw")
    }
}



/**
 * {
 *     id: identifier of schema
 *     attrNames: array of attribute name strings
 *     name: Schema's name string
 *     version: Schema's version string
 *     ver: Version of the Schema json
 * }
 * */
class Schema(json: JSONObject) : JsonDataObject(json) {
    constructor(parseGetSchemaResponse: String) : this(JSONObject(parseGetSchemaResponse))

    val id by json
    val name by json
    val version by json
    val ver by json
    val seqNo = json.getIntOrNull("seqNo")?.toString()

    val owner: String = id.split(":").first()

    val attrNames: List<String> = json.getJSONArray("attrNames").toList()

    val filter: String
        get() = """{name:${name},version:${version},owner:${owner}}"""

    fun isValid() = (seqNo != null)
}

/**
 * {
 *     id: string - identifier of credential definition
 *     schemaId: string - identifier of stored in ledger schema
 *     type: string - type of the credential definition. CL is the only supported type now.
 *     tag: string - allows to distinct between credential definitions for the same issuer and schema
 *     value: Dictionary with Credential Definition's data: {
 *         primary: primary credential public key,
 *         Optional<revocation>: revocation credential public key
 *     },
 *     ver: Version of the Credential Definition json
 * }
 * */
class CredentialDefinition(json: JSONObject) : JsonDataObject(json) {
    constructor(parseGetCredDefResponse: String) : this(JSONObject(parseGetCredDefResponse))

    val id by json
    val type by json
    val tag by json
    val ver by json

    val schemaSeqNo = json.getIntOrNull("schemaId")?.toString()

    val owner: String = id.split(":").first()
    val value = Data(json.getJSONObject("value"))

    val filter: String
        get() = """{schemaSeqNo:${schemaSeqNo},owner:${owner}}"""

    class Data(valueJson: JSONObject){
       val primaryPubKey = valueJson.get("primary").toString()
       val revocationPubKey = valueJson.getStringOrNull("revocation")
    }
}