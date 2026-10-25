package com.ambitie.tvmcp.mcp

import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.doubleValue(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray

fun stringProperty(
    description: String,
    enumValues: List<String>? = null,
    default: String? = null
): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enumValues != null) put("enum", buildJsonArray { enumValues.forEach { add(it) } })
    if (default != null) put("default", default)
}

fun integerProperty(
    description: String,
    minimum: Long? = null,
    maximum: Long? = null,
    default: Long? = null
): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    if (minimum != null) put("minimum", minimum)
    if (maximum != null) put("maximum", maximum)
    if (default != null) put("default", default)
}

fun numberProperty(
    description: String,
    minimum: Double? = null,
    maximum: Double? = null,
    default: Double? = null
): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
    if (minimum != null) put("minimum", minimum)
    if (maximum != null) put("maximum", maximum)
    if (default != null) put("default", default)
}

fun booleanProperty(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
    if (default != null) put("default", default)
}

fun arrayProperty(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}

fun objectSchema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList()
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { properties.forEach { (key, value) -> put(key, value) } })
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(it) } })
    }
}
