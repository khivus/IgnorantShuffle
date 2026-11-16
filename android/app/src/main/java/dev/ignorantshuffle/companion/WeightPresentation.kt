package dev.ignorantshuffle.companion

import java.util.Locale
import kotlin.math.abs

object WeightPresentation {
    fun number(value: Double): String = String.format(Locale.ROOT,"%.4f",if(abs(value)<0.00005) 0.0 else value).trimEnd('0').trimEnd('.')
    fun summary(base: Double, score: Double): String {
        val points=base*score
        return "${number(base)} ${if(points<0) "−" else "+"} ${number(abs(points))} = ${number(base+points)}"
    }
    fun scoreFromPoints(base: Double, points: Double, limit: Double): Double {
        require(base.isFinite() && base>=0 && points.isFinite() && abs(points)<=base*limit+1e-9) { "Adjustment exceeds this track's smart limit" }
        require(base>0 || points==0.0) { "A zero base weight only supports a zero adjustment" }
        return if(base==0.0) 0.0 else (points/base).coerceIn(-limit,limit)
    }
}
