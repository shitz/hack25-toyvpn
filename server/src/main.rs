use anyhow::{Context, Result};
use clap::Parser;
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
        .up(); // On Mac this might utilize utun. On Linux tun0.

    #[cfg(target_os = "linux")]
    config.platform(|config| {
        config.packet_information(false);
    });

    let dev = tun::create_as_async(&config).context("Failed to create TUN device")?;

    // We use tokio::io::split to get a read handle and a write handle
    let (mut tun_reader, mut tun_writer) = tokio::io::split(dev);

    // 2. Setup UDP Socket
    let bind_addr = format!("0.0.0.0:{}", args.port);
    let socket = UdpSocket::bind(&bind_addr).await.context("Failed to bind UDP socket")?;
    let socket = Arc::new(socket);
    let send_sock = socket.clone();
    let recv_sock = socket.clone();

    println!("VPN Server listening on {}", bind_addr);
    println!("TUN interface configured: {}/{}", args.tun_ip, args.tun_mask);

    // Shared state for the client address
    let client_addr: Arc<Mutex<Option<SocketAddr>>> = Arc::new(Mutex::new(None));
    let client_addr_read = client_addr.clone();
    let client_addr_write = client_addr.clone();

    // Buffer for UDP reading
    let mut udp_buf = [0u8; 4096];
    // Buffer for TUN reading
    let mut tun_buf = [0u8; 4096];

    loop {
        tokio::select! {
            // Received packet from UDP -> Write to TUN
            res = recv_sock.recv_from(&mut udp_buf) => {
                match res {
                    Ok((n, src)) => {
                        // Update connected client
                        {
                            let mut lock = client_addr_write.lock().unwrap();
                            if *lock != Some(src) {
                                println!("New client connected from: {}", src);
                                *lock = Some(src);
                            }
                        }

                        // Write packet to TUN
                        use tokio::io::AsyncWriteExt;
                        if let Err(e) = tun_writer.write_all(&udp_buf[..n]).await {
                             eprintln!("Failed to write to TUN: {}", e);
                        }
                    }
                    Err(e) => eprintln!("UDP recv error: {}", e),
                }
            }

            // Received packet from TUN -> Send to UDP Client
            res = tokio::io::AsyncReadExt::read(&mut tun_reader, &mut tun_buf) => {
                 match res {
                    Ok(n) if n > 0 => {
                        let packet = &tun_buf[..n];
                        let target = {
                            *client_addr_read.lock().unwrap()
                        };

                        if let Some(addr) = target {
                            if let Err(e) = send_sock.send_to(packet, addr).await {
                                eprintln!("Failed to send to UDP client: {}", e);
                            }
                        }
                    }
                    Ok(_) => {
                        // EOF
                        break;
                    }
                    Err(e) => eprintln!("TUN recv error: {}", e),
                 }
            }
        }
    }

    Ok(())
}
