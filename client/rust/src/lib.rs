use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;

use anyhow::Context;
use edge_token::dummy_edge_app_token;
use edge_tun::client::{ClientBuilder, Control, Incoming, Outgoing};
use edge_tun::PSEUDO_SECURE_SERVER_SECRET;
use quinn::crypto::rustls::QuicClientConfig;
use quinn::EndpointConfig;
use rustls::ClientConfig;
use scion_proto::address::SocketAddr as ScionSocketAddr;
use scion_stack::scionstack::ScionStackBuilder;
use std::str::FromStr;
use std::time::Duration;
use url::Url;

mod client;

// ----- User-defined types that must exist BEFORE include_scaffolding! -----

pub struct Route {
    pub destination: String,
    pub prefix_length: i32,
}

pub struct VpnClientConfig {
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
    runtime: Runtime,
    connection: Mutex<Option<ToyVpnClientConnection>>,
}

pub struct ToyVpnClientConnection {
    edge_read: Incoming,
    edge_write: Outgoing,
    ctrl: Control,
}

impl Default for ToyVpnClient {
    fn default() -> Self {
        Self::new()
    }
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
            runtime: tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .expect("Failed to create Tokio runtime"),
            connection: Mutex::new(None),
        }
    }

    pub fn handshake(
        &self,
        snap_token: String,
        endhost_api: String,
        edgetun_server: String,
    ) -> Result<VpnClientConfig, VpnError> {
        log::info!("Starting handshake");

        let edgetun_server = ScionSocketAddr::from_str(&edgetun_server).unwrap();
        let endhost_api = Url::from_str(&endhost_api).unwrap();

        let (edge_read, edge_write, ctrl) = self
            .runtime
            .block_on(async {
                let quic_conn = establish_quic_conn(endhost_api, snap_token, edgetun_server)
                    .await
                    .context("Failed to establish QUIC connection to snap")?;

                let (edge_read, edge_write, ctrl) = ClientBuilder::default()
                    .with_initial_mtu(1280)
                    .with_initial_auth_token(dummy_edge_app_token())
                    .connect(quic_conn)
                    .await
                    .expect("Failed to establish edgetun client connection");

                log::info!("edgetun client connection established");
                log::info!("Advertised routes: {:?}", ctrl.advertised_routes());

                anyhow::Ok((edge_read, edge_write, ctrl))
            })
            .map_err(|e| VpnError::StartFailed(e.to_string()))?;

        let ip = ctrl
            .assigned_addresses().first()
            .cloned()
            .ok_or(VpnError::StartFailed(
                "No assigned address from edgetun server".into(),
            ))?;

        let mut routes = Vec::new();

        for route in ctrl.advertised_routes() {
            routes.push(Route {
                destination: route.network().to_string(),
                prefix_length: route.prefix_len() as i32,
            });
        }

        self.connection
            .lock()
            .unwrap()
            .replace(ToyVpnClientConnection {
                edge_read,
                edge_write,
                ctrl,
            });

        Ok(VpnClientConfig {
            client_ip: ip.to_string(),
            routes,
        })
    }

    pub fn start(&self, tun_fd: i32, callback: Box<dyn VpnCallback>) -> Result<(), VpnError> {
        let stop_signal = self.stop_signal.clone();
        let callback: Arc<dyn VpnCallback> = Arc::from(callback);

        // Take the connection
        let connection = self
            .connection
            .lock()
            .unwrap()
            .take()
            .ok_or(VpnError::StartFailed(
                "VPN connection not established. Call handshake() first.".into(),
            ))?;

        let rt = self.runtime.handle().clone();
        std::thread::spawn(move || {
            rt.block_on(async move {
                log::info!("Rust VPN Thread started");
                let res = client::run_vpn(tun_fd, connection, callback.clone(), stop_signal).await;
                if let Err(e) = res {
                    log::error!("VPN Loop Error: {e:?}");
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

/// Establishes a QUIC connection to the edge app server via the given SNAP.
async fn establish_quic_conn(
    endhost_api_addr: url::Url,
    auth_token: String,
    server_addr: ScionSocketAddr,
) -> anyhow::Result<quinn::Connection> {
    let scion_stack = ScionStackBuilder::new(endhost_api_addr)
        .with_auth_token(auth_token)
        .build()
        .await
        .context("Failed to create SCION stack")?;

    let (cert_der, _server_config) = scion_sdk_utils::test::generate_cert(
        PSEUDO_SECURE_SERVER_SECRET,
        vec!["localhost".into()],
        vec![b"edgetun".to_vec()],
    );
    let mut roots = rustls::RootCertStore::empty();
    roots.add(cert_der).unwrap();

    let mut client_crypto = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    client_crypto.alpn_protocols = vec![b"edgetun".to_vec()];

    let mut transport_config = quinn::TransportConfig::default();
    // 5 secs == 1/6 default idle time
    transport_config.keep_alive_interval(Some(Duration::from_secs(5)));
    let mut client_config =
        quinn::ClientConfig::new(Arc::new(QuicClientConfig::try_from(client_crypto).unwrap()));
    client_config.transport_config(Arc::new(transport_config));
    let mut endpoint = scion_stack
        .quic_endpoint(None, EndpointConfig::default(), None, None)
        .await
        .unwrap();

    endpoint.set_default_client_config(client_config);

    log::info!("created quic endpoint, connecting to edge app server");

    let conn = endpoint
        .connect(server_addr, "localhost")
        .context("Failed to initialize connection to edge app server")?
        .await
        .context("Failed to establish connection to edge app server")?;

    Ok(conn)
}

// ----- Include UniFFI scaffolding AFTER defining the types -----
uniffi::include_scaffolding!("toyvpn");
