# ToyVPN

A SCION VPN client for Android, based on the Anapaya SCION endhost SDK and `edgetun` as the VPN tunnel protocol.

## Architecture

The application is built as a standard Android VPN client using the `VpnService` API. The core networking logic is implemented in Rust and integrated into the Android application using [UniFFI](https://github.com/mozilla/uniffi-rs).

- **Android App (`client/app`)**: The UI and system integration layer. It manages the `VpnService` lifecycle and communicates with the Rust backend.
- **Rust Core (`client/rust`)**: Handles the SCION connectivity and VPN tunneling.
  - **SCION Stack**: Uses `scion-stack` and `scion-proto` from the Anapaya SCION endhost SDK to establish connectivity over the SCION network.
  - **VPN Tunnel**: Uses `edge-tun` to encapsulate IP packets from the Android `VpnService` and transport them over QUIC/SCION to a remote gateway.
  - **Integration**: Exposes a high-level API to Kotlin via UniFFI.

## Build Instructions

### Prerequisites

1. **Android Development Environment**:

   - Android Studio (or command-line tools).
   - Android NDK (install via SDK Manager).

2. **Rust Toolchain**:

   - Install Rust: [rustup.rs](https://rustup.rs/)
   - Add Android targets:

     ```bash
     rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
     ```

   - Install `cargo-ndk`:

     ```bash
     cargo install cargo-ndk
     ```

### Building the Client

The Android build process is configured to automatically build the Rust components and generate the necessary Kotlin bindings.

1. **Configure `local.properties`**:
   Ensure your `client/local.properties` file points to your Android SDK and NDK. If you open the project in Android Studio, this is usually done automatically.

   ```properties
   sdk.dir=/path/to/android/sdk
   ndk.dir=/path/to/android/sdk/ndk/<version>
   ```

2. **Build with Gradle**:
   Navigate to the `client` directory and run the build:

   ```bash
   cd client
   ./gradlew assembleDebug
   ```

   This command will:

   - Compile the Rust code for all configured Android architectures.
   - Generate the UniFFI Kotlin bindings.
   - Package everything into an APK (`client/app/build/outputs/apk/debug/app-debug.apk`).
