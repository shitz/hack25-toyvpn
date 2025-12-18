# ToyVPN

A toy VPN implementation consisting of a Rust server and an Android client (Kotlin + Rust).

## Architecture

*   **Server (`server/`)**: A Rust application that listens on a UDP socket, creates a TUN interface, and uses `iptables` for NAT to forward traffic.
*   **Client (`client/`)**: An Android application that uses `VpnService`. The tunneling logic (TUN <-> UDP) is implemented in Rust (`client/rust`) and integrated via UniFFI.

## 1. Server Setup (Linux)

The server requires a Linux host with root access and `iptables`.

### 1.1 Compile
```bash
cd server
cargo build --release
```
The binary will be located at `target/release/toyvpn-server`.

### 1.2 Configure Network
Use the provided script to enable IP forwarding and NAT:
```bash
sudo ./setup_host.sh [wan_interface]
```

### 1.3 Run
```bash
# Default (Port 12345, TUN 10.0.0.1/24)
sudo ./target/release/toyvpn-server

# Custom
sudo ./target/release/toyvpn-server --port 50000 --tun-ip 192.168.100.1
```

## 2. Client Setup (Android)

### 2.1 Prerequisites

#### Rust Toolchain
1. **Install Rust**: [rustup.rs](https://rustup.rs/)
2. **Add Android Targets**:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```
3. **Install `cargo-ndk`**:
   ```bash
   cargo install cargo-ndk
   ```

#### Android SDK Tools (NDK & CMake)
You must install the NDK and CMake via Android Studio:
1. Open Android Studio.
2. Go to **Settings** (or **Preferences**) > **Languages & Frameworks** > **Android SDK**.
3. Select the **SDK Tools** tab.
4. Check **NDK (Side by side)** and **CMake**.
5. Click **Apply** to download and install them.

### 2.2 Build and Run
1. Open the `client/` directory in Android Studio.
2. Wait for Gradle sync.
   - *Note*: The build process (`client/app/build.gradle.kts`) automatically invokes `cargo-ndk` to build the Rust library and `uniffi-bindgen` to generate Kotlin bindings.
3. Connect an Android device (with USB Debugging enabled).
4. Run the **app** configuration.

## 3. Usage

1. **Server**: Start the server and note the public IP and Port.
2. **Client**:
   - Launch **ToyVPN**.
   - Enter the **Server Address** (Use numeric IP, e.g., `1.2.3.4`).
   - Enter **Server Port** (Default: 12345).
   - Enter **Local Client IP** (Address for the phone's TUN interface, e.g., `10.0.0.2`).
   - Click **Connect**.
3. **Permissions**: Accept the VPN connection request dialog.
4. **traffic**: Verify traffic flow and stats updates in the UI.

## Troubleshooting

### Debugging Packet Flow
Use **Logcat** in Android Studio filtered by tag `ToyVPN`.
- `VPN Interface established`: Service started.
- `Tunnel connected`: Rust client connected to UDP socket.
- `Upstream: read N bytes`: Android -> Server traffic.
- `Downstream: read N bytes`: Server -> Android traffic.

### Common Issues
- **No Internet**: Verify `sysctl net.ipv4.ip_forward=1` on the server and `iptables` NAT rules.
- **Build Fails**: Ensure `cargo` is in your PATH. If Gradle cannot find `cargo`, you may need to adjust the `cargoPath` in `client/app/build.gradle.kts`.
