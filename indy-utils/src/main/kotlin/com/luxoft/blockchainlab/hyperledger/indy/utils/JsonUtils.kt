package com.luxoft.blockchainlab.hyperledger.indy.utils

import net.corda.core.serialization.SerializationCustomSerializer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.experimental.buildIterator
import kotlin.reflect.KProperty

fun JSONArray.toList(): List<String> = List(length()) { i -> getString(i) }

fun JSONArray.toObjectList(): List<JSONObject> = List(length()) { i -> getJSONObject(i) }

operator fun JSONArray.iterator(): Iterator<String> = buildIterator {
    for(i in 0 until length())
        yield(get(i).toString())
}

operator fun JSONObject.getValue(thisRef: Any?, property: KProperty<*>): String = getString(property.name)

fun JSONObject.getStringOrNull(key: String) = if(has(key) && get(key) != JSONObject.NULL) getString(key) else null
fun JSONObject.getIntOrNull(key: String): Int? = if (has(key) && get(key) != JSONObject.NULL) getInt(key) else null

fun JSONObject.toStringMap(): Map<String, String> {
    val keys = keySet() as Set<String>
    return keys.associateBy({ it }, { getString(it) })
}

fun JSONObject.toObjectMap(): Map<String, JSONObject> {
    val keys = keySet() as Set<String>
    return keys.associateBy({ it }, { getJSONObject(it) })
}

public class JSONObjectCordaSerializer : SerializationCustomSerializer<JSONObject, JSONObjectCordaSerializer.Proxy> {
    class Proxy(val json: String)

    override fun fromProxy(proxy: Proxy): JSONObject = JSONObject(proxy.json)

    override fun toProxy(obj: JSONObject): Proxy = Proxy(obj.toString())
}