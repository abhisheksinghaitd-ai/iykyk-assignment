package com.iykyk.facecollage.domain.model

/**
 * One identity, resolved by [com.iykyk.facecollage.data.cluster.AppearanceClusterer] from one or
 * more [Appearance]s whose track embeddings clustered together.
 */
data class Person(
    val id: Int,
    val appearances: List<Appearance>,
    val representative: Detection
) {
    val appearanceCount: Int get() = appearances.size
}
