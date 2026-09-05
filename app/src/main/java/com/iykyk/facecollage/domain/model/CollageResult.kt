package com.iykyk.facecollage.domain.model

import android.graphics.Bitmap

/** Final output of a full pipeline run: the rendered collage plus every person it depicts. */
data class CollageResult(
    val bitmap: Bitmap,
    val people: List<Person>,
    val videoLabel: String
) {
    val totalAppearances: Int get() = people.sumOf { it.appearanceCount }
}
