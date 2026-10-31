package se.klang.dsp

import kotlin.math.sqrt

/** Ordinary least-squares line y = intercept + slope·x. */
internal data class LineFit(val slope: Double, val intercept: Double, val rSquared: Double, val residualRms: Double, val n: Int) {
    fun at(x: Double) = intercept + slope * x
}

internal fun fitLine(xs: DoubleArray, ys: DoubleArray, from: Int = 0, to: Int = xs.size, stride: Int = 1): LineFit? {
    var n = 0
    var sx = 0.0; var sy = 0.0
    var i = from
    while (i < to) { sx += xs[i]; sy += ys[i]; n++; i += stride }
    if (n < 3) return null
    val mx = sx / n; val my = sy / n
    var sxx = 0.0; var sxy = 0.0; var syy = 0.0
    i = from
    while (i < to) {
        val dx = xs[i] - mx; val dy = ys[i] - my
        sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        i += stride
    }
    if (sxx <= 0.0) return null
    val slope = sxy / sxx
    val intercept = my - slope * mx
    val r2 = if (syy <= 0.0) 1.0 else (sxy * sxy) / (sxx * syy)
    var ss = 0.0
    i = from
    while (i < to) { val r = ys[i] - (intercept + slope * xs[i]); ss += r * r; i += stride }
    return LineFit(slope, intercept, r2, sqrt(ss / n), n)
}
