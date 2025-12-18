use anyhow::{Context, Result};
use clap::Parser;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use tokio::net::UdpSocket;
use tun::Configuration;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Port to listen on
    #[arg(short, long, default_value_t = 12345)]
    port: u16,

    /// TUN interface IP address (IPv4)
    #[arg(long, default_value = "10.0.0.1")]
    tun_ip: String,

    /// TUN interface Netmask (IPv4)
    #[arg(long, default_value = "255.255.255.0")]
    tun_mask: String,
}

struct IpPool {
    network: u32,
    mask: u32,
    server_ip: u32,
    allocated: HashMap<u32, ()>,
}

impl IpPool {
    fn new(ip: Ipv4Addr, mask: Ipv4Addr) -> Self {
        let ip_u32: u32 = ip.into();
        let mask_u32: u32 = mask.into();
        let network = ip_u32 & mask_u32;

        let mut pool = Self {
            network,
            mask: mask_u32,
            server_ip: ip_u32,
            allocated: HashMap::new(),
        };
        // Reserve server IP
        pool.allocated.insert(ip_u32, ());
        // Reserve network and broadcast
        pool.allocated.insert(network, ());
        pool.allocated.insert(network | !mask_u32, ());
        pool
    }

    fn allocate(&mut self) -> Option<Ipv4Addr> {
        // Simple linear search for now
        let range_size = (!self.mask) + 1;
        for i in 1..range_size {
            let candidate = self.network + i;
            if !self.allocated.contains_key(&candidate) {
                self.allocated.insert(candidate, ());
                return Some(Ipv4Addr::from(candidate));
            }
        }
        None
    }

    #[allow(dead_code)]
    fn release(&mut self, ip: Ipv4Addr) {
        let ip_u32: u32 = ip.into();
        if ip_u32 != self.server_ip {
            self.allocated.remove(&ip_u32);
        }
    }
}

struct ClientManager {
    by_ip: HashMap<Ipv4Addr, SocketAddr>,
    by_addr: HashMap<SocketAddr, Ipv4Addr>,
    pool: IpPool,
}

impl ClientManager {
    fn new(pool: IpPool) -> Self {
        Self {
            by_ip: HashMap::new(),
            by_addr: HashMap::new(),
            pool,
        }
    }

    fn register(&mut self, addr: SocketAddr) -> Option<Ipv4Addr> {
        if let Some(ip) = self.by_addr.get(&addr) {
            return Some(*ip);
        }
        if let Some(ip) = self.pool.allocate() {
            self.by_ip.insert(ip, addr);
            self.by_addr.insert(addr, ip);
            println!("Assigned {} to {}", ip, addr);
            Some(ip)
        } else {
            None
        }
    }

    fn get_addr(&self, ip: &Ipv4Addr) -> Option<SocketAddr> {
        self.by_ip.get(ip).cloned()
    }

    fn get_ip(&self, addr: &SocketAddr) -> Option<Ipv4Addr> {
        self.by_addr.get(addr).cloned()
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    let tun_ip: Ipv4Addr = args.tun_ip.parse().context("Invalid TUN IP")?;
    let tun_mask: Ipv4Addr = args.tun_mask.parse().context("Invalid TUN Netmask")?;
    let tun_octets = tun_ip.octets();
    let mask_octets = tun_mask.octets();

    // 1. Setup TUN interface
    let mut config = Configuration::default();
    config
        .address((tun_octets[0], tun_octets[1], tun_octets[2], tun_octets[3]))
        .netmask((mask_octets[0], mask_octets[1], mask_octets[2], mask_octets[3]))
        .up();

    #[cfg(target_os = "linux")]
    config.platform(|config| {
        config.packet_information(false);
    });

    let dev = tun::create_as_async(&config).context("Failed to create TUN device")?;
    let (mut tun_reader, mut tun_writer) = tokio::io::split(dev);

    // 2. Setup UDP Socket
    let bind_addr = format!("0.0.0.0:{}", args.port);
    let socket = UdpSocket::bind(&bind_addr).await.context("Failed to bind UDP socket")?;
    let socket = Arc::new(socket);
    let send_sock = socket.clone();
    let recv_sock = socket.clone();

    println!("VPN Server listening on {}", bind_addr);
    println!("TUN interface configured: {}/{}", args.tun_ip, args.tun_mask);

    let pool = IpPool::new(tun_ip, tun_mask);
    let manager = Arc::new(Mutex::new(ClientManager::new(pool)));
    let manager_read = manager.clone();
    let manager_write = manager.clone();

    let mut udp_buf = [0u8; 4096];
    let mut tun_buf = [0u8; 4096];

    loop {
        tokio::select! {
            // UDP -> TUN
            res = recv_sock.recv_from(&mut udp_buf) => {
                match res {
                    Ok((n, src)) => {
                        let packet = &udp_buf[..n];
                        if n >= 2 && packet[0] == 0x00 && packet[1] == 0x01 {
                            // Handshake Request
                            println!("Handshake request from {}", src);
                            let mut mgr = manager_write.lock().unwrap();
                            if let Some(ip) = mgr.register(src) {
                                // Response: [0x00, 0x02, IP(4), RouteCount(1), Route1(8)...]

                                let mut response = Vec::new();
                                response.push(0x00);
                                response.push(0x02);
                                response.extend_from_slice(&ip.octets());

                                // Route Count: 1
                                response.push(1);
                                // Route 1: 0.0.0.0/0
                                response.extend_from_slice(&[0, 0, 0, 0]); // Address
                                response.extend_from_slice(&[0, 0, 0, 0]); // Mask (0.0.0.0 for /0)

                                if let Err(e) = send_sock.send_to(&response, src).await {
                                    eprintln!("Failed to send handshake response: {}", e);
                                }
                            } else {
                                eprintln!("No IPs available for {}", src);
                            }
                        } else if n > 0 && packet[0] == 0x45 {
                            // Data Packet
                            let allowed = {
                                let mgr = manager_read.lock().unwrap();
                                mgr.get_ip(&src).is_some()
                            };

                            if allowed {
                                use tokio::io::AsyncWriteExt;
                                if let Err(e) = tun_writer.write_all(packet).await {
                                     eprintln!("Failed to write to TUN: {}", e);
                                }
                            }
                        }
                    }
                    Err(e) => eprintln!("UDP recv error: {}", e),
                }
            }

            // TUN -> UDP
            res = tokio::io::AsyncReadExt::read(&mut tun_reader, &mut tun_buf) => {
                 match res {
                    Ok(n) if n > 0 => {
                        let packet = &tun_buf[..n];
                        // Extract Dest IP (IPv4)
                        // Header length is usually 20 bytes. Dest IP is at offset 16.
                        if n >= 20 && packet[0] >> 4 == 4 {
                            let dest_ip = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);

                            let target = {
                                let mgr = manager_read.lock().unwrap();
                                mgr.get_addr(&dest_ip)
                            };

                            if let Some(addr) = target {
                                if let Err(e) = send_sock.send_to(packet, addr).await {
                                    eprintln!("Failed to send to UDP client: {}", e);
                                }
                            }
                        }
                    }
                    Ok(_) => break,
                    Err(e) => eprintln!("TUN recv error: {}", e),
                 }
            }
        }
    }

    Ok(())
}
