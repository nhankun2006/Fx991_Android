package io.paimon.fx991.engine

import java.util.Random

// ---------------------------------------------------------------------------
// Batch B: Scientific constants / unit conversion / SI prefixes / random numbers / variable registers (all pure Kotlin,
// no Compose dependency -> can be regression-tested directly on JVM)
// ---------------------------------------------------------------------------

/** A scientific constant */
class SciConstant(val symbol: String, val name: String, val value: Double, val unit: String)

/** Scientific constants table (CODATA 2018/2019 SI defined values) */
object SciConstants {

    @JvmField
    val ALL: List<SciConstant> = listOf(
        SciConstant("c", "Speed of light in vacuum", 299792458.0, "m/s"),
        SciConstant("h", "Planck constant", 6.62607015e-34, "J·s"),
        SciConstant("ħ", "Reduced Planck constant", 1.054571817e-34, "J·s"),
        SciConstant("e", "Elementary charge", 1.602176634e-19, "C"),
        SciConstant("NA", "Avogadro constant", 6.02214076e23, "/mol"),
        SciConstant("k", "Boltzmann constant", 1.380649e-23, "J/K"),
        SciConstant("G", "Newtonian constant of gravitation", 6.67430e-11, "m³/(kg·s²)"),
        SciConstant("g", "Standard acceleration of gravity", 9.80665, "m/s²"),
        SciConstant("me", "Electron mass", 9.1093837015e-31, "kg"),
        SciConstant("mp", "Proton mass", 1.67262192369e-27, "kg"),
        SciConstant("mn", "Neutron mass", 1.67492749804e-27, "kg"),
        SciConstant("mμ", "Muon mass", 1.883531627e-28, "kg"),
        SciConstant("u", "Unified atomic mass unit", 1.66053906660e-27, "kg"),
        SciConstant("R", "Molar gas constant", 8.314462618, "J/(mol·K)"),
        SciConstant("F", "Faraday constant", 96485.33212, "C/mol"),
        SciConstant("Vm", "Molar volume of ideal gas (273.15 K)", 0.02241396954, "m³/mol"),
        SciConstant("ε0", "Electric constant (vacuum permittivity)", 8.8541878128e-12, "F/m"),
        SciConstant("μ0", "Magnetic constant (vacuum permeability)", 1.25663706212e-6, "N/A²"),
        SciConstant("atm", "Standard atmosphere", 101325.0, "Pa"),
        SciConstant("σ", "Stefan-Boltzmann constant", 5.670374419e-8, "W/(m²·K⁴)"),
        SciConstant("R∞", "Rydberg constant", 10973731.568160, "/m"),
        SciConstant("μB", "Bohr magneton", 9.2740100783e-24, "J/T"),
        SciConstant("α", "Fine-structure constant", 7.2973525693e-3, "—"),
        SciConstant("Ry", "Rydberg energy", 2.1798723611e-18, "J"),
    )

    @JvmField
    val COUNT: Int = ALL.size

    /** Batch K3: Lookup constant by symbol (for main-line const( in-place reference); returns null if not found */
    fun find(symbol: String): SciConstant? = ALL.firstOrNull { it.symbol == symbol }
}

// ---------------------------------------------------------------------------
// Unit conversion
// ---------------------------------------------------------------------------

/** A unit; `value_base = value × factor` (affine conversions like temperature use affine branch) */
class UnitDef(val name: String, val symbol: String, val factor: Double)

/** A unit category */
class UnitCategory(
    val name: String,
    val baseSymbol: String,
    val units: List<UnitDef>,
    /** Affine (non-multiplicative) conversion, e.g. temperature */
    val affine: Boolean = false,
)

object UnitConvert {

    @JvmField
    val LENGTH = UnitCategory("Length", "m", listOf(
        UnitDef("Nanometer", "nm", 1e-9),
        UnitDef("Micrometer", "μm", 1e-6),
        UnitDef("Millimeter", "mm", 1e-3),
        UnitDef("Centimeter", "cm", 1e-2),
        UnitDef("Meter", "m", 1.0),
        UnitDef("Kilometer", "km", 1e3),
        UnitDef("Inch", "in", 0.0254),
        UnitDef("Foot", "ft", 0.3048),
        UnitDef("Yard", "yd", 0.9144),
        UnitDef("Mile", "mi", 1609.344),
        UnitDef("Nautical Mile", "nmi", 1852.0),
    ))

    @JvmField
    val MASS = UnitCategory("Mass", "kg", listOf(
        UnitDef("Milligram", "mg", 1e-6),
        UnitDef("Gram", "g", 1e-3),
        UnitDef("Kilogram", "kg", 1.0),
        UnitDef("Tonne", "t", 1e3),
        UnitDef("Ounce", "oz", 0.028349523125),
        UnitDef("Pound", "lb", 0.45359237),
    ))

    @JvmField
    val TIME = UnitCategory("Time", "s", listOf(
        UnitDef("Millisecond", "ms", 1e-3),
        UnitDef("Second", "s", 1.0),
        UnitDef("Minute", "min", 60.0),
        UnitDef("Hour", "h", 3600.0),
        UnitDef("Day", "d", 86400.0),
        UnitDef("Week", "wk", 604800.0),
    ))

    @JvmField
    val AREA = UnitCategory("Area", "m²", listOf(
        UnitDef("Square Centimeter", "cm²", 1e-4),
        UnitDef("Square Meter", "m²", 1.0),
        UnitDef("Square Kilometer", "km²", 1e6),
        UnitDef("Hectare", "ha", 1e4),
        UnitDef("Mu", "mu", 2000.0 / 3.0),
        UnitDef("Square Foot", "ft²", 0.09290304),
        UnitDef("Acre", "acre", 4046.8564224),
    ))

    @JvmField
    val VOLUME = UnitCategory("Volume", "L", listOf(
        UnitDef("Milliliter", "mL", 1e-3),
        UnitDef("Cubic Centimeter", "cm³", 1e-3),
        UnitDef("Liter", "L", 1.0),
        UnitDef("Cubic Meter", "m³", 1e3),
        UnitDef("US Gallon", "gal", 3.785411784),
        UnitDef("UK Gallon", "gal(UK)", 4.54609),
    ))

    /** Temperature: non-multiplicative relationship, handled separately */
    @JvmField
    val TEMPERATURE = UnitCategory("Temperature", "°C", listOf(
        UnitDef("Celsius", "°C", 1.0),
        UnitDef("Fahrenheit", "°F", 1.0),
        UnitDef("Kelvin", "K", 1.0),
    ), affine = true)

    @JvmField
    val CATEGORIES: List<UnitCategory> =
        listOf(LENGTH, MASS, TIME, AREA, VOLUME, TEMPERATURE)

    /** Convert by category object (non-multiplicative conversions handled by affine branch) */
    fun convert(category: UnitCategory, fromIndex: Int, toIndex: Int, value: Double): Double {
        require(fromIndex in category.units.indices && toIndex in category.units.indices) {
            "Unit index out of bounds"
        }
        if (!category.affine) {
            val base = value * category.units[fromIndex].factor
            return base / category.units[toIndex].factor
        }
        val celsius = toCelsius(category.units[fromIndex].symbol, value)
        return fromCelsius(category.units[toIndex].symbol, celsius)
    }

    /** Convert by category index */
    fun convert(categoryIndex: Int, fromIndex: Int, toIndex: Int, value: Double): Double =
        convert(CATEGORIES[categoryIndex], fromIndex, toIndex, value)

    /** Temperature -> Celsius */
    fun toCelsius(symbol: String, v: Double): Double = when (symbol) {
        "°C" -> v
        "°F" -> (v - 32.0) * 5.0 / 9.0
        "K" -> v - 273.15
        else -> throw NumericError("Unknown temperature unit $symbol")
    }

    /** Celsius -> Temperature */
    fun fromCelsius(symbol: String, c: Double): Double = when (symbol) {
        "°C" -> c
        "°F" -> c * 9.0 / 5.0 + 32.0
        "K" -> c + 273.15
        else -> throw NumericError("Unknown temperature unit $symbol")
    }

    // -------------------------------------------------------------------
    // Batch K3: Symbol lookup for in-place conversion conv(value, fromUnit, toUnit)
    //   - Temperature keypad cannot type °, accepts shorthands C / F / K
    //   - gal(UK) with parentheses cannot be typed, main line only reaches gal (US); UK gallon via panel path noted in docs
    // -------------------------------------------------------------------

    /** Typable temperature unit symbol -> internal symbol */
    private fun tempAlias(symbol: String): String = when (symbol) {
        "C", "°C" -> "°C"
        "F", "°F" -> "°F"
        "K" -> "K"
        else -> symbol
    }

    /** Find which category / unit a symbol belongs to; temperature accepts shorthands. Returns null if not found */
    fun findUnit(symbol: String): Pair<UnitCategory, UnitDef>? {
        val s = tempAlias(symbol)
        for (cat in CATEGORIES) {
            cat.units.firstOrNull { it.symbol == s }?.let { return cat to it }
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// SI prefixes
// ---------------------------------------------------------------------------

class SiPrefix(val name: String, val symbol: String, val factor: Double)

object SiPrefixes {

    @JvmField
    val ALL: List<SiPrefix> = listOf(
        SiPrefix("Yotta", "Y", 1e24),
        SiPrefix("Zetta", "Z", 1e21),
        SiPrefix("Exa", "E", 1e18),
        SiPrefix("Peta", "P", 1e15),
        SiPrefix("Tera", "T", 1e12),
        SiPrefix("Giga", "G", 1e9),
        SiPrefix("Mega", "M", 1e6),
        SiPrefix("Kilo", "k", 1e3),
        SiPrefix("Hecto", "h", 1e2),
        SiPrefix("Deca", "da", 1e1),
        SiPrefix("(None)", "", 1.0),
        SiPrefix("Deci", "d", 1e-1),
        SiPrefix("Centi", "c", 1e-2),
        SiPrefix("Milli", "m", 1e-3),
        SiPrefix("Micro", "μ", 1e-6),
        SiPrefix("Nano", "n", 1e-9),
        SiPrefix("Pico", "p", 1e-12),
        SiPrefix("Femto", "f", 1e-15),
        SiPrefix("Atto", "a", 1e-18),
        SiPrefix("Zepto", "z", 1e-21),
        SiPrefix("Yocto", "y", 1e-24),
    )

    /** Default selected index for "(None)" */
    @JvmField
    val NONE_INDEX: Int = ALL.indexOfFirst { it.factor == 1.0 }

    /** Convert: value in [fromIndex] prefix -> in [toIndex] prefix */
    fun convert(fromIndex: Int, toIndex: Int, value: Double): Double {
        require(fromIndex in ALL.indices && toIndex in ALL.indices) { "SI prefix index out of bounds" }
        return value * ALL[fromIndex].factor / ALL[toIndex].factor
    }

    /** Batch K3: Lookup prefix by symbol (for main-line si( in-place conversion); returns null for empty or unknown */
    fun find(symbol: String): SiPrefix? =
        if (symbol.isEmpty()) null else ALL.firstOrNull { it.symbol == symbol }
}

// ---------------------------------------------------------------------------
// Random numbers
// ---------------------------------------------------------------------------

object RandomOps {

    private val rng = Random()

    /** Ran#: uniform distribution on 0 <= x < 1 */
    fun uniform(): Double = rng.nextDouble()

    /** RanInt(a, b): uniform integer on [a, b] */
    fun ranInt(a: Long, b: Long): Long {
        if (b < a) throw NumericError("Upper bound cannot be less than lower bound")
        if (a > 9_000_000_000L || b > 9_000_000_000L || a < -9_000_000_000L) {
            throw NumericError("Random integer range is too large")
        }
        val span = b - a + 1
        return a + (rng.nextDouble() * span).toLong().coerceIn(0, span - 1)
    }

    /** RanInt(a, b, n): n integers on [a, b] */
    fun ranInts(a: Long, b: Long, n: Int): List<Long> {
        if (n < 1) throw NumericError("Count must be ≥ 1")
        if (n > 100_000) throw NumericError("Too many iterations (> 100000)")
        return List(n) { ranInt(a, b) }
    }
}

// ---------------------------------------------------------------------------
// Variable registers (STO): A–F / x / y
// ---------------------------------------------------------------------------

class Registers {

    private val vars = LinkedHashMap<String, Double>()

    /** Store into a variable, returns confirmation message for user */
    fun store(name: String, value: Double): String {
        if (name !in VARIABLES) throw NumericError("Unknown variable $name")
        vars[name] = value
        return "Stored $name = " + CalcEngine.format(value, 10, null)
    }

    fun get(name: String): Double? = vars[name]

    /** Batch G: Delete a single variable in algebra area */
    fun remove(name: String): Boolean = vars.remove(name) != null

    /** Variable map for expression substitution (A–F / x / y only) */
    fun snapshot(): Map<String, Double> = LinkedHashMap(vars)

    fun clearAll() {
        vars.clear()
    }

    val isEmpty: Boolean get() = vars.isEmpty()

    companion object {
        /** Variable keys: A–F numeric variables + x / y (M is memory, not included here) */
        @JvmField
        val VARIABLES: List<String> = listOf("A", "B", "C", "D", "E", "F", "x", "y")
    }
}
