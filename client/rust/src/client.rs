use crate::{ToyVpnClientConnection, VpnCallback};
use bytes::Bytes;
use std::io::{Read, Write};
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::io::unix::AsyncFd;
use tokio::sync::Notify;

const BUFFER_SIZE: usize = 4096;

pub async fn run_vpn(
    tun_fd: i32,
    edgetun: ToyVpnClientConnection,
    callback: Arc<dyn VpnCallback>,
    stop_signal: Arc<Notify>,
) -> anyhow::Result<()> {
    log::info!("run_vpn starting with tun_fd={tun_fd}");

    // 1. Prepare TUN device
    // Set to non-blocking mode for AsyncFd
    set_nonblocking(tun_fd)?;

    // Create File from raw fd. unsafe because we assume ownership of fd.
    // We wrap it in AsyncFd to use with tokio
    let tun_file = unsafe { std::fs::File::from_raw_fd(tun_fd) };
    let tun = Arc::new(AsyncFd::new(tun_file)?);

    // 3. Stats
    let total_tx = Arc::new(AtomicU64::new(0));
    let total_rx = Arc::new(AtomicU64::new(0));

    // 4. Spawn Tasks

    let ToyVpnClientConnection {
        mut edge_read,
        mut edge_write,
        ctrl: _ctrl,
    } = edgetun;

    // Task: TUN -> UDP (Uplink)
    let tun_reader = tun.clone();
    let tx_stats = total_tx.clone();
    let stop_tx = stop_signal.clone();

    let tx_task = tokio::spawn(async move {
        log::info!("Tx task started");
        let mut buf = [0u8; BUFFER_SIZE];
        loop {
            tokio::select! {
                _ = stop_tx.notified() => break,
                guard = tun_reader.readable() => {
                    match guard {
                        Ok(mut guard) => {
                            match guard.try_io(|inner| inner.get_ref().read(&mut buf)) {
                                Ok(Ok(n)) => {
                                    if n == 0 {
                                        log::info!("TUN read EOF");
                                        break;
                                    }
                                    tx_stats.fetch_add(n as u64, Ordering::Relaxed);
                                    if let Err(e) = edge_write.send_wait(Bytes::copy_from_slice(&buf[..n])).await {
                                        log::error!("UDP send error: {e}");
                                    }
                                }
                                Ok(Err(e)) => {
                                    log::error!("TUN read error: {e}");
                                    break;
                                }
                                Err(_would_block) => continue,
                            }
                        }
                        Err(e) => {
                            log::error!("TUN readable error: {e}");
                            break;
                        }
                    }
                }
            }
        }
        log::info!("Tx task exiting");
    });

    // Task: UDP -> TUN (Downlink)
    let tun_writer = tun.clone();
    let rx_stats = total_rx.clone();
    let stop_rx = stop_signal.clone();

    let rx_task = tokio::spawn(async move {
        log::info!("Rx task started");
        loop {
            tokio::select! {
                _ = stop_rx.notified() => break,
                res = edge_read.receive() => {
                    match res {
                        Ok(buf) => {
                            rx_stats.fetch_add(buf.len() as u64, Ordering::Relaxed);

                            // Write to TUN
                            // We loop until we can write or error
                            loop {
                                let mut guard = match tun_writer.writable().await {
                                    Ok(g) => g,
                                    Err(e) => {
                                        log::error!("TUN writable error: {e}");
                                        return;
                                    }
                                };

                                match guard.try_io(|inner| inner.get_ref().write(&buf)) {
                                    Ok(Ok(_)) => break,
                                    Ok(Err(e)) => {
                                        log::error!("TUN write error: {e}");
                                        return;
                                    }
                                    Err(_would_block) => continue,
                                }
                            }
                        }
                        Err(e) => {
                            log::error!("UDP recv error: {e}");
                            break;
                        }
                    }
                }
            }
        }
        log::info!("Rx task exiting");
    });

    // Task: Stats
    let stats_tx = total_tx.clone();
    let stats_rx = total_rx.clone();
    let stop_stats = stop_signal.clone();
    let cb = callback.clone();

    let stats_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(1));
        loop {
            tokio::select! {
                _ = stop_stats.notified() => break,
                _ = interval.tick() => {
                    cb.on_stats_update(
                        stats_tx.load(Ordering::Relaxed),
                        stats_rx.load(Ordering::Relaxed)
                    );
                }
            }
        }
        log::info!("Stats task exiting");
    });

    // Wait for stop signal or any task failure
    tokio::select! {
        _ = stop_signal.notified() => {
            log::info!("Stop signal received in main loop");
        }
        _ = tx_task => {
            log::info!("Tx task finished unexpectedly");
        }
        _ = rx_task => {
            log::info!("Rx task finished unexpectedly");
        }
        _ = stats_task => {
            log::info!("Stats task finished unexpectedly");
        }
    }

    // Ensure all tasks are cleaned up
    stop_signal.notify_waiters();

    log::info!("VPN run_vpn completed");
    Ok(())
}

fn set_nonblocking(fd: i32) -> anyhow::Result<()> {
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags < 0 {
            anyhow::bail!("fcntl F_GETFL failed");
        }
        if libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
            anyhow::bail!("fcntl F_SETFL failed to set nonblocking");
        }
    }
    log::info!("Set fd {fd} to non-blocking mode");
    Ok(())
}
