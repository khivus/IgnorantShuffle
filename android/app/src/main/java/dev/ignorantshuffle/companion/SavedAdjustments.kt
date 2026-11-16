package dev.ignorantshuffle.companion

/** Separate from base weights; factors use the same atomic portable file protocol. */
object SavedAdjustments {
    const val FILENAME = "IgnorantShuffle.adjustments.tsv"
    fun documents(source: WeightDocuments): WeightDocuments = object : WeightDocuments by source {
        private fun name(value: String) = value.replace(PortableWeights.FILENAME, FILENAME)
        override fun find(name: String) = source.find(name(name))
        override fun create(name: String) = source.create(name(name))
        override fun rename(id: String, name: String) = source.rename(id, name(name))
    }
    fun updated(current: Double, delta: Double, limit: Double) = (current+delta).coerceIn(-limit,limit)
}
