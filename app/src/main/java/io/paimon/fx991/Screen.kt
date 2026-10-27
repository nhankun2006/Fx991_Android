package io.paimon.fx991

/** Main Screens (switched via MODE menu) */
enum class Screen(val title: String) {
    CALC("Calculation"),
    ODE("Differential Eq"),

    // ---- Batch C ----
    CMPLX("Complex"),
    MATRIX("Matrix"),
    VECTOR("Vector"),
    STAT("Statistics"),
    DISTR("Distribution"),
    FUNC_HELP("Function Help"),

    // ---- Batch D ----
    EQUATION("Equation"),
    BASEN("Base-N"),
    TABLE("Table"),
    RATIO("Ratio"),

    // ---- Batch E: Photo solve (AI vision recognition -> fill main line) ----
    PHOTO_SOLVE("Photo Solve"),

    // ---- Batch G: GeoGebra-style Algebra / Fourier series / Complex analysis ----
    ALGEBRA("Algebra"),
    FOURIER("Fourier Series"),
    CPLXF("Complex Analysis"),
}
