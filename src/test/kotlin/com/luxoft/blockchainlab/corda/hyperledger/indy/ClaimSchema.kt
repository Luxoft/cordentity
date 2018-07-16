package com.luxoft.blockchainlab.corda.hyperledger.indy

interface Schema {
    val schemaName: String
    val schemaVersion: String
    val schemaAttrs: List<String>

    fun getSchemaProposal(): String

}

class SchemaPerson : Schema {

    override val schemaName = "schema_name"
    override val schemaVersion = "1.0"
    val schemaAttr1 = "attr1"
    val schemaAttr2 = "attr2"

    private val schemaProposal = """ {"$schemaAttr1":{"raw":"%s", "encoded":"%s"}, "$schemaAttr2":{"raw":"%s", "encoded":"%s"} }"""

    val schemaKey = "{ \"name\":\"${schemaName}\",\"version\":\"${schemaVersion}\",\"did\":\"%s\"}"
    val claimOffer = "{\"issuer_did\":\"%s\", \"schema_key\": ${schemaKey} }"

    override val schemaAttrs = listOf(schemaAttr1, schemaAttr2)

    override fun getSchemaProposal(): String {
        return schemaProposal
    }
}

class SchemaEducation: Schema {

    override val schemaName = "schema_education"
    override val schemaVersion = "1.0"
    val schemaAttr1 = "attrX"
    val schemaAttr2 = "attrY"

    private val schemaProposal = """ {"$schemaAttr1":{"raw":"%s", "encoded":"%s"}, "$schemaAttr2":{"raw":"%s", "encoded":"%s"} }"""

    val schemaKey = "{ \"name\":\"${schemaName}\",\"version\":\"${schemaVersion}\",\"did\":\"%s\"}"
    val claimOffer = "{\"issuer_did\":\"%s\", \"schema_key\": ${schemaKey} }"

    override val schemaAttrs = listOf(schemaAttr1, schemaAttr2)

    override fun getSchemaProposal(): String {
        return schemaProposal
    }
}

class SchemaHappiness : Schema {

    override val schemaName = "schema_happiness"
    override val schemaVersion = "1.0"
    val issuerDid: String = ""
    val schemaAttrForKiss = "isMySweetheart"
    val schemaAttrForDrink = "age"

    private val schemaProposal = """ {"$schemaAttrForKiss":{"raw":"%s", "encoded":"%s"}, "$schemaAttrForDrink":{"raw":"%s", "encoded":"%s"} }"""

    val schemaKey = "{ \"name\":\"${schemaName}\",\"version\":\"${schemaVersion}\",\"did\":\"%s\"}"
    val claimOffer = "{\"issuer_did\":\"%s\", \"schema_key\": ${schemaKey} }"

    override val schemaAttrs = listOf(schemaAttrForKiss, schemaAttrForDrink)

    override fun getSchemaProposal(): String {
        return schemaProposal
    }
}
