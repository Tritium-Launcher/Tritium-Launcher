package io.github.footermandev.tritium.core.project.template

import kotlinx.serialization.json.JsonElement

/**
 * Has tools for migrating Project Templates
 */
object MigrationRegistry {
    private val steps = mutableMapOf<String, MutableMap<Int, (JsonElement) -> JsonElement>>()

    fun registerStep(
        typeId: String,
        fromVersion: Int,
        step: (JsonElement) -> JsonElement
    ) {
        steps.getOrPut(typeId) { mutableMapOf() }[fromVersion] = step
    }

    fun migrate(typeId: String, from: Int, to: Int, input: JsonElement): JsonElement {
        var v = from
        var cur = input
        val map = steps[typeId] ?: emptyMap()
        while(v < to) {
            val step = map[v] ?: throw IllegalStateException("No migration for $typeId from $v -> ${v + 1}")
            cur = step(cur)
            v++
        }
        return cur
    }
}