package com.luxoft.blockchainlab.hyperledger.indy.model

import org.json.JSONObject

data class Did(val json: String)

data class Pairwise(val json: String) {
    val did = JSONObject(json).get("my_did").toString()
    val metadata = JSONObject(json).get("metadata").toString()
}

data class ClaimSchema(val json: String) {

    val id = JSONObject(json).get("seqNo").toString()
    val dest = JSONObject(json).get("dest").toString()
    val data = JSONObject(json).get("data").toString()
    val txnTime = JSONObject(json).get("txnTime").toString()

    fun isValid(): Boolean {
        return ("null" != id) && ("null" != txnTime)
    }
}

data class ClaimDef(val json: String) {

    val id = JSONObject(json).get("seqNo").toString()
    val txnTime = JSONObject(json).get("txnTime").toString()

    val data = JSONObject(json).get("data").toString()

    fun isValid(): Boolean {
        return ("null" != id) && ("null" != txnTime)
    }
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

data class Proof(val json: String, val usedSchemas: String, val usedClaimDefs: String)
