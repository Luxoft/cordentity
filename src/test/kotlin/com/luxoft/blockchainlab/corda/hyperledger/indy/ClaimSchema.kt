package com.luxoft.blockchainlab.corda.hyperledger.indy

interface Schema {
    fun getSchemaName():String
    fun getSchemaVersion():String
    fun getSchemaAttrs(): List<String>

    fun getSchemaProposal(): String

}

class SchemaPerson : Schema {

    private val schemaName = "schema_name"
    private val schemaVersion = "1.0"
    val schemaAttr1 = "attr1"
    val schemaAttr2 = "attr2"

    private val schemaProposal = """{"$schemaAttr1":{"raw":"%s", "encoded":"%s"}, "$schemaAttr2":{"raw":"%s", "encoded":""%s"} }"""

    val schemaKey = "{ \"name\":\"${schemaName}\",\"version\":\"${schemaVersion}\",\"did\":\"%s\"}"
    val claimOffer = "{\"issuer_did\":\"%s\", \"schema_key\": ${schemaKey} }"

    override fun getSchemaAttrs(): List<String> {
        return listOf(schemaAttr1, schemaAttr2)
    }

    override fun getSchemaVersion(): String {
        return schemaVersion
    }

    override fun getSchemaName(): String {
        return schemaName
    }

    override fun getSchemaProposal(): String {
        return schemaProposal
    }
}

class SchemaEducation: Schema {

    private val schemaName = "schema_education"
    private val schemaVersion = "1.0"
    val schemaAttr1 = "attrX"
    val schemaAttr2 = "attrY"

    private val schemaProposal = """{"$schemaAttr1":{"raw":"%s", "encoded":"%s"}, "$schemaAttr2":{"raw":"%s", "encoded":""%s"} }"""

    val schemaKey = "{ \"name\":\"${schemaName}\",\"version\":\"${schemaVersion}\",\"did\":\"%s\"}"
    val claimOffer = "{\"issuer_did\":\"%s\", \"schema_key\": ${schemaKey} }"

    override fun getSchemaAttrs(): List<String> {
        return listOf(schemaAttr1, schemaAttr2)
    }

    override fun getSchemaVersion(): String {
        return schemaVersion
    }

    override fun getSchemaName(): String {
        return schemaName
    }

    override fun getSchemaProposal(): String {
        return schemaProposal
    }
}

class SchemaHappiness : Schema {

    private val schemaName = "schema_happiness"
    private val schemaVersion = "1.0"
    val issuerDid: String = ""
    val schemaAttrForKiss = "isMySweetheart"
    val schemaAttrForDrink = "age"

    private val schemaProposal = """{"$schemaAttrForKiss":{"raw":"%s", "encoded":"%s"}, "$schemaAttrForDrink":{"raw":"%s", "encoded":""%s"} }"""

    val schemaKey = "{ \"name\":\"${schemaName}\",\"version\":\"${schemaVersion}\",\"did\":\"%s\"}"
    val claimOffer = "{\"issuer_did\":\"%s\", \"schema_key\": ${schemaKey} }"

    override fun getSchemaAttrs(): List<String> {
        return listOf(schemaAttrForKiss, schemaAttrForDrink)
    }

    override fun getSchemaVersion(): String {
        return schemaVersion
    }

    override fun getSchemaName(): String {
        return schemaName
    }

    override fun getSchemaProposal(): String {
        return schemaProposal
    }
}
