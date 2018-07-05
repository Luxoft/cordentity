package com.luxoft.blockchainlab.hyperledger.indy.model

import org.hyperledger.indy.sdk.ledger.LedgerResults
import org.json.JSONArray
import org.json.JSONObject
import kotlin.reflect.KProperty

data class Did(val json: String)

data class Pairwise(val json: String) {
    val did = JSONObject(json).get("my_did").toString()
    val metadata = JSONObject(json).get("metadata").toString()
}

data class ClaimOffer(val json: String, val proverDid: String) {
    val issuerDid = JSONObject(json).get("issuer_did").toString()
    val schemaKey = JSONObject(json).get("schema_key").toString()
}

data class ClaimReq(val json: String)

data class Claim(val json: String) {

    val issuerDid = JSONObject(json).get("issuer_did").toString()
    val signature = JSONObject(json).get("signature").toString()
    val schemaKey = JSONObject(json).get("schema_key").toString()
}

data class ClaimRef(val json: String) {

    private val attributes = JSONObject(json).getJSONObject("attrs").toString()

    val issuerDid = JSONObject(json).get("issuer_did").toString()
    val schemaKey = JSONObject(json).get("schema_key").toString()

    val referentClaim = JSONObject(json).get("referent").toString()

}

data class ProofReq(val json: String) {
    val attributes = JSONObject(json).get("requested_attrs").toString()
    val predicates = JSONObject(json).get("requested_predicates").toString()

    fun getPredicateValue(key: String, filter: String): String {
        return JSONObject(predicates).getJSONObject(key).get(filter).toString()
    }

    fun getAttributeValue(key: String, filter: String): String {
        return JSONObject(attributes).getJSONObject(key).get(filter).toString()
    }
}

data class Proof(val json: String, val usedSchemas: String, val usedClaimDefs: String) {

    private val revealedAttrs = JSONObject(json)
            .getJSONObject("requested_proof")
            .getJSONObject("revealed_attrs")
            .toString()

    fun isAttributeExist(attr: String): Boolean {
        val attrs = JSONObject(revealedAttrs)
        val keys = attrs.keySet()

        return keys.map { attrs.get(it.toString()) as JSONArray }
                .associateBy { it.get(1) }
                .containsKey(attr)
    }

    fun getAttributeValue(attr: String, proofReq: String): String? {

        val attributesMapping = JSONObject(ProofReq(proofReq).attributes)
        val groupedByAttr = attributesMapping.keySet()
                .associateBy { attributesMapping[it as String] to it }
                .keys.map { (it.first as JSONObject)["name"] to it.second }.toMap()

        val idx = groupedByAttr[attr]

        return (JSONObject(revealedAttrs).get(idx) as JSONArray)
                .get(1).toString()

        //val mapping = grouped.keys.map { (it.first as JSONObject)["name"] to it.second }.toMap()
        // val idx = JSONObject(mapping)[tion.name]
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
class Schema(parseGetSchemaResponse: LedgerResults.ParseResponseResult) {
    val json = JSONObject(parseGetSchemaResponse.objectJson)

    val id by json
    val name by json
    val version by json
    val ver by json

    val attrNames: List<String> = json.getJSONArray("attrNames").toList()

    /* "$did:3:$SIGNATURE_TYPE:${schema.id}:$TAG" */
    val seqNo = id.split(":").get(3).toInt()
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
class CredentialDefinition(parseGetCredDefResponse: LedgerResults.ParseResponseResult) {
    val json = JSONObject(parseGetCredDefResponse.objectJson)

    val id by json
    val schemaId by json
    val type by json
    val tag by json
    val ver by json

    val value = Data(json.getJSONObject("value"))

    class Data(valueJson: JSONObject){
       val primaryPubKey = valueJson.getString("primary")
       val revocationPubKey = valueJson.getStringOrNull("revocation")
    }
}


inline fun <reified E> JSONArray.toList(): List<E> = List(length()) { i -> get(i) as E }

operator fun JSONObject.getValue(thisRef: Any?, property: KProperty<*>): String = getString(property.name)

fun JSONObject.getStringOrNull(key: String) = if(has(key)) getString(key) else null
