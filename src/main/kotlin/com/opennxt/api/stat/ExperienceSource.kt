package com.opennxt.api.stat

abstract class ExperienceSource(val boostFactor: Double) {
    object Default: ExperienceSource(1.0)

    object Lamp: ExperienceSource(1.0)
    object Quest: ExperienceSource(1.0)
}
