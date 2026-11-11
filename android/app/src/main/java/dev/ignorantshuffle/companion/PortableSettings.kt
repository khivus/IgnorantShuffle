package dev.ignorantshuffle.companion

/** Common rules only. Device permissions, appearance and playback integration stay local. */
data class PortableSettings(val rules: Settings, val balance: Boolean, val balanceStrength: Double, val smart: SmartWeightOptions) {
    fun values(): Map<String, Double> = linkedMapOf("version" to 1.0, "default" to rules.defaultWeight,
        "minimum" to rules.minimumWeight, "cooldown" to rules.cooldown.toDouble(), "finite" to flag(rules.finite),
        "balance" to flag(balance), "balanceStrength" to balanceStrength, "smart" to flag(smart.enabled),
        "strength" to smart.strength, "limit" to smart.limit, "skips" to flag(smart.skips),
        "completions" to flag(smart.completions), "replays" to flag(smart.replays), "volume" to flag(smart.volume))
    fun encode() = PortableWeights.write(values()).replace("weights v1: weight<TAB>track", "settings v1: value<TAB>key")
    companion object {
        const val FILENAME = "IgnorantShuffle.settings.tsv"
        private fun flag(value: Boolean) = if(value) 1.0 else 0.0
        fun decode(text: String): PortableSettings {
            val v = PortableWeights.parse(text)
            fun n(key: String) = requireNotNull(v[key]) { "Missing shared setting: $key" }
            fun b(key: String): Boolean { val x = n(key); require(x == 0.0 || x == 1.0); return x == 1.0 }
            require(n("version") == 1.0) { "Unsupported shared settings version" }
            val cooldown = n("cooldown"); require(cooldown % 1.0 == 0.0 && cooldown in 0.0..10000.0)
            val rules = Settings(n("default"), cooldown.toInt(), b("finite"), n("minimum")).also { it.validate() }
            val strength = n("balanceStrength"); require(strength in 0.25..1.0)
            val smart = SmartWeightOptions(b("smart"), n("strength"), n("limit"), b("skips"), b("completions"), b("replays"), b("volume")).also { it.validate() }
            return PortableSettings(rules, b("balance"), strength, smart)
        }
        // Reuse the verified temporary-file/backup/rollback protocol with a separate filename.
        fun documents(source: WeightDocuments): WeightDocuments = object : WeightDocuments by source {
            private fun name(value: String) = value.replace(PortableWeights.FILENAME, FILENAME)
            override fun find(name: String) = source.find(name(name))
            override fun create(name: String) = source.create(name(name))
            override fun rename(id: String, name: String) = source.rename(id, name(name))
        }
    }
}
