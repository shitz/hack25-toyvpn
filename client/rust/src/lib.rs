use std::sync::{Arc, Mutex};
use std::os::fd::FromRawFd;
use std::net::Ipv4Addr;
use tokio::runtime::Runtime;

mod client;

// ----- User-defined types that must exist BEFORE include_scaffolding! -----

pub struct Route {
    pub destination: String,
    pub prefix_length: i32,
}

pub struct ClientConfig {
    pub client_ip: String,
    pub routes: Vec<Route>,
}

/// Callback interface for VPN events (defined by user, called from Kotlin)
pub trait VpnCallback: Send + Sync {
    fn on_stats_update(&self, tx_bytes: u64, rx_bytes: u64);
    fn on_stop(&self, reason: String);
}

/// Error type for VPN operations
#[derive(thiserror::Error, Debug)]
pub enum VpnError {
    #[error("Failed to start: {0}")]
    StartFailed(String),
}

/// The main VPN client object
pub struct ToyVpnClient {
    stop_signal: Arc<tokio::sync::Notify>,
    socket: Mutex<Option<std::net::UdpSocket>>,
}

impl ToyVpnClient {
    pub fn new() -> Self {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("ToyVpnRust"),
        );
        Self {
            stop_signal: Arc::new(tokio::sync::Notify::new()),
            socket: Mutex::new(None),
        }
    }

    pub fn handshake(&self, udp_fd: i32) -> Result<ClientConfig, VpnError> {
        let socket = unsafe { std::net::UdpSocket::from_raw_fd(udp_fd) };

        // Send Handshake Request: [0x00, 0x01]
        let req = [0x00, 0x01];
        socket.send(&req).map_err(|e| VpnError::StartFailed(format!("Send failed: {}", e)))?;

        // Receive Response
        let mut buf = [0u8; 1024];
        // Set timeout for handshake
        socket.set_read_timeout(Some(std::time::Duration::from_secs(5)))
            .map_err(|e| VpnError::StartFailed(format!("Set timeout failed: {}", e)))?;

        let (n, _) = socket.recv_from(&mut buf)
            .map_err(|e| VpnError::StartFailed(format!("Recv failed: {}", e)))?;

        // Parse Response: [0x00, 0x02, IP(4), RouteCount(1), Route1(8)...]
        if n < 7 || buf[0] != 0x00 || buf[1] != 0x02 {
             return Err(VpnError::StartFailed("Invalid handshake response".into()));
        }

        let ip = Ipv4Addr::new(buf[2], buf[3], buf[4], buf[5]);
        let route_count = buf[6] as usize;

        let mut routes = Vec::new();
        let mut offset = 7;

        for _ in 0..route_count {
            if offset + 8 > n {
                break;
            }
            let r_ip = Ipv4Addr::new(buf[offset], buf[offset+1], buf[offset+2], buf[offset+3]);
            let r_mask = Ipv4Addr::new(buf[offset+4], buf[offset+5], buf[offset+6], buf[offset+7]);
            offset += 8;

            // Convert mask to prefix length
            let mask_u32: u32 = r_mask.into();
            let prefix_len = mask_u32.count_ones() as i32;

            routes.push(Route {
                destination: r_ip.to_string(),
                prefix_length: prefix_len,
            });
        }

        // Store socket for start()
        // Reset timeout
        socket.set_read_timeout(None).ok();
        *self.socket.lock().unwrap() = Some(socket);

        Ok(ClientConfig {
            client_ip: ip.to_string(),
            routes,
        })
    }

    pub fn start(
        &self,
        tun_fd: i32,
        callback: Box<dyn VpnCallback>,
    ) -> Result<(), VpnError> {
        let stop_signal = self.stop_signal.clone();
        let callback: Arc<dyn VpnCallback> = Arc::from(callback);

        // Take the socket
        let socket = self.socket.lock().unwrap().take()
            .ok_or(VpnError::StartFailed("Handshake not performed".into()))?;

        std::thread::spawn(move || {
            let rt = match Runtime::new() {
                Ok(rt) => rt,
                Err(e) => {
                    log::error!("Failed to create Runtime: {}", e);
                    callback.on_stop(e.to_string());
                    return;
                }
            };

            rt.block_on(async move {
                log::info!("Rust VPN Thread started");
                let res = client::run_vpn(tun_fd, socket, callback.clone(), stop_signal).await;
                if let Err(e) = res {
                    log::error!("VPN Loop Error: {:?}", e);
                    callback.on_stop(e.to_string());
                } else {
                    log::info!("VPN Loop finished cleanly");
                    callback.on_stop("Stopped".to_string());
                }
            });
        });

        Ok(())
    }

    pub fn stop(&self) {
        log::info!("Stop signal received");
        self.stop_signal.notify_one();
    }
}

// ----- Include UniFFI scaffolding AFTER defining the types -----
uniffi::include_scaffolding!("toyvpn");
