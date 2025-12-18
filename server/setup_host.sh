#!/bin/bash
set -e

# Usage: sudo ./setup_host.sh [wan_interface]

WAN_IF=$1

if [ -z "$WAN_IF" ]; then
    # Try to detect default interface
    WAN_IF=$(ip route | grep default | awk '{print $5}' | head -n1)
    if [ -z "$WAN_IF" ]; then
        echo "Error: Could not detect WAN interface. Please provide it as an argument."
        echo "Usage: sudo ./setup_host.sh <interface_name>"
        exit 1
    fi
    echo "Detected WAN interface: $WAN_IF"
fi

echo "Enabling IP forwarding..."
sysctl -w net.ipv4.ip_forward=1

echo "Configuring iptables for NAT (Masquerade) on $WAN_IF..."
# Allow traffic from TUN
iptables -A FORWARD -i tun0 -o "$WAN_IF" -j ACCEPT
iptables -A FORWARD -i "$WAN_IF" -o tun0 -m state --state RELATED,ESTABLISHED -j ACCEPT

# Enable NAT
iptables -t nat -A POSTROUTING -o "$WAN_IF" -j MASQUERADE

echo "Done. Host is configured."
echo "Note: This configuration is not persistent across reboots."
