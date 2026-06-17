package borg.trikeshed.polyglot.graal

/**
 * TestTarget - simple class with various field/method patterns for pointcut interception.
 * Compiled to JVM classfile, used by GraalPointcutHarness tests.
 */
class TestTarget {
    companion object {
        // Static fields for P_GET / P_SET pointcuts
        var staticInt: Int = 42
        var staticString: String = "initial"
        val staticFinalInt: Int = 100
    }

    // Instance fields for L_GET / L_SET pointcuts
    var instanceInt: Int = 0
    var instanceString: String = "default"
    val instanceFinalInt: Int = 200

    fun noOp(): Int = 1

    fun doSetInstanceInt(value: Int) {
        instanceInt = value  // L_SET
    }

    fun doGetInstanceInt(): Int {
        return instanceInt  // L_GET
    }

    fun doSetStaticInt(value: Int) {
        staticInt = value  // P_SET
    }

    fun doGetStaticInt(): Int {
        return staticInt  // P_GET
    }

    fun compute(x: Int, y: Int): Int {
        val a = x + y       // L_GET x, L_GET y
        val b = a * 2       // L_GET a
        instanceInt = b     // L_SET instanceInt
        return b
    }
}

/**
 * Polymorphic target for virtual dispatch pointcuts
 */
open class PolyTarget {
    open fun virtualMethod(): Int = 1
    open val openProperty: Int
        get() = instanceField
    var instanceField: Int = 0
}

class DerivedTarget : PolyTarget() {
    override fun virtualMethod(): Int = 2
    override val openProperty: Int
        get() = super.openProperty + 10
}