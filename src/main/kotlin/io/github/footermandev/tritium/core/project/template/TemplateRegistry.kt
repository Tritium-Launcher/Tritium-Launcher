package io.github.footermandev.tritium.core.project.template

object TemplateRegistry {
    private val descriptors = mutableMapOf<String, TemplateDescriptor<*>>()

    fun register(descriptor: TemplateDescriptor<*>) {
        descriptors[descriptor.typeId] = descriptor
    }

    fun unregister(typeId: String) {
        descriptors.remove(typeId)
    }

    fun get(typeId: String): TemplateDescriptor<*>? = descriptors[typeId]

    fun all(): Collection<TemplateDescriptor<*>> = descriptors.values
}