# Fx991_Android

An open-source, ad-free clone of the Casio Fx-991 scientific calculator for Android devices with full advanced features unlocked.

Built modernly from scratch with **Kotlin**, **Jetpack Compose**, and **Material Design 3**, featuring a Natural Display LCD screen, an exact rational arithmetic core, and an extensive suite of scientific computation tools.

---

## Important: Native Physical Device Target (No Emulator)

> [!IMPORTANT]
> **This project is built natively for physical Android devices and does not use or require any Android emulator.**
>
> Modern smartphones utilize the 64-bit ARM architecture (`arm64-v8a`). To keep the build lean and fast, the build configuration defaults to native `arm64-v8a` binaries (saving over 30 MB in APK size).
> All builds are deployed directly to a connected physical Android device using the Android CLI tool `adb` over USB or wireless debugging.

---

## System Prerequisites (Linux / Fedora)

To build and install the application from the Linux command line, ensure the following tools are set up on your machine:

### 1. Java Development Kit (JDK 21)
- **Version**: OpenJDK 21 (`java-21-openjdk`)
- **Environment Variable**: `JAVA_HOME` must be explicitly exported in your shell profile (e.g. `~/.bashrc`):
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
  export PATH=$JAVA_HOME/bin:$PATH
  ```

#### Why is `JAVA_HOME` needed when `java` is already in `PATH`?
- **Executable vs. SDK Root**: The `java` command in your `PATH` is just a pointer to the JVM runtime binary (`/usr/bin/java`). It only allows executing compiled bytecode.
- **Gradle Daemons & SDK Tools**: Gradle launches background worker daemons that locate compiler tools (`javac`, `jar`, `keytool`, `jlink`) and native C/C++ header files (such as JNI headers in `$JAVA_HOME/include` and `$JAVA_HOME/include/linux`) using the `$JAVA_HOME` path.
- **Fedora Alternatives Guardrail**: Linux distributions like Fedora manage multiple installed Java runtimes via `update-alternatives`. If an incompatible Java version (e.g., Java 25) is set as default for the CLI launcher, Android Gradle Plugin will fail. Setting `JAVA_HOME=/usr/lib/jvm/java-21-openjdk` ensures Gradle and AGP always build with the supported Java 21 toolchain.

### 2. Android SDK Command-Line Tools
- **SDK Location**: Configured in `~/.bashrc`:
  ```bash
  export ANDROID_HOME=$HOME/android-sdk
  export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin
  ```
- **Installed Packages**:
  - `platform-tools` (provides `adb`)
  - `build-tools;34.0.0` or higher (up to `36.0.0`)
  - `platforms;android-35` / `platforms;android-36`

### 3. Build System
- **Gradle**: Gradle 8.9+ (or use the provided `./gradlew` wrapper script).

### 4. Physical Android Device
- A real Android smartphone running Android 8.0+ (API 26+) with `arm64-v8a` architecture.
- **Developer Options** enabled on the phone, with **USB Debugging** turned on.

---

## CLI Build & Deployment Workflow

### 1. Verify Device Connection
Connect your Android device via USB cable, authorize USB debugging on your phone prompt, and verify from the CLI:
```bash
adb devices
```
*You should see your device ID listed with status `device`.*

### 2. Build the Debug APK
Run the Gradle wrapper to build the native `arm64-v8a` APK:
```bash
./gradlew assembleDebug
```
The resulting APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Install Directly to Device
Deploy the newly built APK onto your physical phone:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Launch App via CLI
Launch the calculator directly onto your phone screen:
```bash
adb shell am start -n io.paimon.fx991/.MainActivity
```

### 5. View Logs (Optional)
Monitor the app logcat output filtered by the application:
```bash
adb logcat -s Fx991
```

### 6. Run Regression Test Suite
Run the 22 automated JVM regression test suites (over 1,600 assertions):
```bash
./tools/run-tests.sh
```

---

## Key Features & Highlights

- **Natural Display LCD Screen**: 2D vertical fraction stacking, nested radicals, superscripts, definite integrals, and 4-way D-pad cursor navigation.
- **Dual-Track Exact Engine**:
  - Exact rational arithmetic (`Rational(BigInteger num, BigInteger den)`) preserves exact fractional results without floating-point precision decay.
  - `S⇔D` button toggles between proper/improper fractions, mixed numbers, and decimals.
- **Unified Main-Line Input**: No need to switch between calculator modes—calculate matrices (`MatA×MatB`), vectors (`cross(VctA,VctB)`), angle operators (`9∠60+5∠6`), distributions, and statistics directly on the main line.
- **Direct Equation Solver**: Type an equation containing variables (e.g. `2x+3=7`, `x^2+1=0`, or `sin(x)=0.5`) and press `=` to solve for all real and complex roots.
- **System Theme Integration**: Fully responsive Material Design 3 day and night themes, while faithfully maintaining Casio-style orange `SHIFT` and purple `ALPHA` keys.
- **Offline Equation OCR**: Integrated ML Kit model running 100% offline on your device to capture and solve equations from paper.
- **Zero Third-Party Math Libraries**: The mathematical parser, symbolic engine, matrix operations, and Runge-Kutta numerical solvers are implemented natively in pure Kotlin.

---

## Documentation

- [INFO.md](file:///home/nhankuun2006/Documents/Fx991_Android/INFO.md): Detailed capability overview, main-line syntax, and format capabilities.
- [REFERENCE.md](file:///home/nhankuun2006/Documents/Fx991_Android/REFERENCE.md): Design specifications, keypad mapping, and architectural rules.
