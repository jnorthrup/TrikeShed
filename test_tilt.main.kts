import kotlin.math.*

// The prompt says "tilt does not accumulate floating-point error"
// Is it 1.0 - cos(tilt)?
val tilt = 1e-8
val lift_bad = 100.0 * (1.0 - cos(tilt))
// for small x, 1 - cos(x) is x^2/2.
// 2 * sin(x/2)^2 is much more stable!
val lift_good = 100.0 * 2.0 * sin(tilt / 2.0).pow(2)
println(lift_bad)
println(lift_good)
