use std::sync::Arc;
use tokio::runtime::Runtime;

mod client;

// ----- User-defined types that must exist BEFORE include_scaffolding! -----

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
        }
    }

    pub fn start(
        &self,
        tun_fd: i32,
        server_ip: String,
        server_port: u16,
        _client_ip: String,
        callback: Box<dyn VpnCallback>,
    ) -> Result<(), VpnError> {
        let stop_signal = self.stop_signal.clone();
        let callback: Arc<dyn VpnCallback> = Arc::from(callback);

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
                let res = client::run_vpn(tun_fd, server_ip, server_port, callback.clone(), stop_signal).await;
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
