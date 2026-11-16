package dev.ignorantshuffle.companion

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

object WeightPresentation {
    fun number(value: Double): String = BigDecimal.valueOf(value).setScale(2,RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    fun summary(base: Double, score: Double): String {
        val points=base*score
        return "${number(base)} ${if(points<0) "−" else "+"} ${number(abs(points))}"
    }
    fun scoreFromPoints(base: Double, points: Double, limit: Double): Double {
        require(base.isFinite() && base>=0 && points.isFinite() && abs(points)<=base*limit+1e-9) { "Adjustment exceeds this track's smart limit" }
        require(base>0 || points==0.0) { "A zero base weight only supports a zero adjustment" }
        return if(base==0.0) 0.0 else (points/base).coerceIn(-limit,limit)
    }
}
