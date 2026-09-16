package com.opennxt.model.entity.movement

class RunEnergy {
    @Volatile
    var tenths: Int = MAX_TENTHS
        private set

    @Volatile
    var toggled: Boolean = DEFAULT_TOGGLE
        private set

    var lastSent: Int = -1
        private set

    val displayed: Int get() = tenths / TENTHS_PER_POINT

    fun canRunStep(): Boolean = toggled && tenths >= resumeTenths

    fun setToggled(on: Boolean): Boolean {
        toggled = on
        return toggled
    }

    fun tick(ranThisTick: Boolean): Int {
        tenths += if (ranThisTick) -drainTenths else regenTenths
        if (tenths < 0) tenths = 0
        if (tenths > MAX_TENTHS) tenths = MAX_TENTHS
        return tenths
    }

    fun takeSend(): Int? {
        val d = displayed
        if (d == lastSent) return null
        lastSent = d
        return d
    }

    fun markSent(value: Int) {
        lastSent = value
    }

    fun restore(tenths: Int, toggled: Boolean) {
        this.tenths = tenths.coerceIn(0, MAX_TENTHS)
        this.toggled = toggled
    }

    fun isDefault(): Boolean = tenths == MAX_TENTHS && toggled == DEFAULT_TOGGLE

    companion object {
        const val MAX_TENTHS = 1000

        const val TENTHS_PER_POINT = 10

        val DRAIN_TENTHS: Int =
            System.getProperty("opennxt.runenergy.drainTenths")?.toIntOrNull() ?: 7

        val REGEN_TENTHS: Int =
            System.getProperty("opennxt.runenergy.regenTenths")?.toIntOrNull() ?: 8

        val RESUME_TENTHS_PROPERTY: Int? =
            System.getProperty("opennxt.runenergy.resumeTenths")?.toIntOrNull()

        val DEFAULT_TOGGLE: Boolean =
            System.getProperty("opennxt.runenergy.default")?.lowercase() != "off"

        const val RUN_VARP = 463

        val PROVENANCE: String = provenance()

        private fun provenance(): String {
            val resume = RESUME_TENTHS_PROPERTY
            val zero = if (resume == null) "" else ", resume at $resume"
            return "run energy: max $MAX_TENTHS tenths, drain $DRAIN_TENTHS, regen $REGEN_TENTHS, " +
                "varp $RUN_VARP, default " + (if (DEFAULT_TOGGLE) "run" else "walk") + zero
        }
    }

    var resumeTenths: Int = RESUME_TENTHS_PROPERTY ?: DRAIN_TENTHS

    var drainTenths: Int = DRAIN_TENTHS

    var regenTenths: Int = REGEN_TENTHS
}
