package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.Node

/** Applies the region filter configured on the warehouse-source page. */
internal object WarehouseGroupingCountryResolver {
    fun filter(nodes: List<Node>, selectedCountryCodes: Set<String>?): List<Node> {
        if (selectedCountryCodes == null) return nodes
        return nodes.filter { it.getCountryCode() in selectedCountryCodes }
    }
}
