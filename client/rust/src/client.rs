use std::os::fd::FromRawFd;
use std::os::unix::io::AsRawFd;
use std::sync::Arc;
use std::io::{Read, Write};
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, Notify};
use crate::VpnCallback;

const BUFFER_SIZE: usize = 4096;

pub async fn run_vpn(
    tun_fd: i32,
    server_ip: String,
    server_port: u16,
    callback: Arc<dyn VpnCallback>,
    stop_signal: Arc<Notify>,
) -> anyhow::Result<()> {
    log::info!("run_vpn starting with fd={}, server={}:{}", tun_fd, server_ip, server_port);

    // 1. Create TUN file handles (we'll use two separate handles for read/write threads)
    // Safety: We own this FD from Android. We dup it so we can have separate read/write handles.
    let tun_read_fd = unsafe { libc::dup(tun_fd) };
    let tun_write_fd = unsafe { libc::dup(tun_fd) };

    if tun_read_fd < 0 || tun_write_fd < 0 {
        anyhow::bail!("Failed to dup TUN fd");
    }

    // Close the original fd since we've duped it
    unsafe { libc::close(tun_fd) };

    // Ensure the fds are in BLOCKING mode for our thread-based I/O
    set_blocking(tun_read_fd)?;
    set_blocking(tun_write_fd)?;

    let mut tun_reader = unsafe { std::fs::File::from_raw_fd(tun_read_fd) };
    let mut tun_writer = unsafe { std::fs::File::from_raw_fd(tun_write_fd) };

    // 2. Connect UDP socket
    let remote_addr = format!("{}:{}", server_ip, server_port);
    let udp = Arc::new(UdpSocket::bind("0.0.0.0:0").await?);
    udp.connect(&remote_addr).await?;

    log::info!("UDP socket bound and connected to {}", remote_addr);

    // 3. Create channels for communication between threads
    // TUN Read Thread -> Main Loop: packets to send via UDP
    let (tun_tx, mut tun_rx) = mpsc::channel::<Vec<u8>>(256);
    // Main Loop -> TUN Write Thread: packets received from UDP
    let (udp_tx, mut udp_rx) = mpsc::channel::<Vec<u8>>(256);

    // 4. Spawn TUN Read Thread (blocking read)
    let stop_signal_tun_read = stop_signal.clone();
    let tun_read_handle = std::thread::spawn(move || {
        log::info!("TUN read thread started");
        let mut buf = [0u8; BUFFER_SIZE];
        loop {
            match tun_reader.read(&mut buf) {
                Ok(0) => {
                    log::info!("TUN read: EOF");
                    break;
                }
                Ok(n) => {
                    log::debug!("TUN read: {} bytes", n);
                    if tun_tx.blocking_send(buf[..n].to_vec()).is_err() {
                        log::info!("TUN read: channel closed");
                        break;
                    }
                }
                Err(e) => {
                    // Check if we should stop
                    log::error!("TUN read error: {}", e);
                    break;
                }
            }
        }
        log::info!("TUN read thread exiting");
    });

    // 5. Spawn TUN Write Thread (blocking write)
    let tun_write_handle = std::thread::spawn(move || {
        log::info!("TUN write thread started");
        while let Some(packet) = udp_rx.blocking_recv() {
            if let Err(e) = tun_writer.write_all(&packet) {
                log::error!("TUN write error: {}", e);
                break;
            }
            log::debug!("TUN write: {} bytes", packet.len());
        }
        log::info!("TUN write thread exiting");
    });

    // 6. Async main loop: handles UDP and stats
    let mut total_tx = 0u64;
    let mut total_rx = 0u64;
    let mut stats_interval = tokio::time::interval(std::time::Duration::from_secs(1));
    let mut buf_udp = [0u8; BUFFER_SIZE];

    let udp_recv = udp.clone();

    loop {
        tokio::select! {
            biased; // Check stop signal first

            _ = stop_signal.notified() => {
                log::info!("Stop signal received in main loop");
                break;
            }

            _ = stats_interval.tick() => {
                log::debug!("Stats update: tx={}, rx={}", total_tx, total_rx);
                callback.on_stats_update(total_tx, total_rx);
            }

            // Receive from TUN read thread -> Send to UDP
            Some(packet) = tun_rx.recv() => {
                total_tx += packet.len() as u64;
                if let Err(e) = udp.send(&packet).await {
                    log::error!("UDP send error: {}", e);
                }
            }

            // Receive from UDP -> Send to TUN write thread
            result = udp_recv.recv(&mut buf_udp) => {
                match result {
                    Ok(n) => {
                        total_rx += n as u64;
                        if udp_tx.send(buf_udp[..n].to_vec()).await.is_err() {
                            log::info!("TUN write channel closed");
                            break;
                        }
                    }
                    Err(e) => {
                        log::error!("UDP recv error: {}", e);
                    }
                }
            }
        }
    }

    log::info!("Main loop exiting, cleaning up...");

    // Cleanup: close channels by dropping senders
    drop(tun_rx);
    drop(udp_tx);

    // Wait for threads to finish (they should exit when channels close)
    // Note: tun_read_handle may block on read() - we can't easily interrupt it
    // The TUN fd is closed when tun_reader is dropped

    log::info!("VPN run_vpn completed");
    Ok(())
}

/// Set a file descriptor to blocking mode
fn set_blocking(fd: i32) -> anyhow::Result<()> {
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags < 0 {
            anyhow::bail!("fcntl F_GETFL failed");
        }
        // Clear O_NONBLOCK flag
        if libc::fcntl(fd, libc::F_SETFL, flags & !libc::O_NONBLOCK) < 0 {
            anyhow::bail!("fcntl F_SETFL failed to set blocking");
        }
    }
    log::info!("Set fd {} to blocking mode", fd);
    Ok(())
}
