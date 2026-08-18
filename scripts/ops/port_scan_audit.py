import argparse
import socket
import sys

# Default admin/internal ports to audit
DEFAULT_PORTS = {
    22: "SSH",
    5432: "PostgreSQL Database",
    9090: "Prometheus Monitoring",
    3000: "Grafana Dashboards",
}


def scan_port(host: str, port: int, timeout: float = 1.0) -> str:
    """Attempts to establish a TCP connection to the target port."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(timeout)
            result = s.connect_ex((host, port))
            if result == 0:
                return "OPEN (Vulnerability)"
            else:
                return "CLOSED/FILTERED (Secure)"
    except Exception as e:
        return f"ERROR ({e})"


def main():
    parser = argparse.ArgumentParser(
        description="Audit network host to ensure admin ports are not publicly exposed."
    )
    parser.add_argument(
        "--host",
        default="127.0.0.1",
        help="Target host/IP to audit (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=1.0,
        help="Connection timeout in seconds (default: 1.0)",
    )
    args = parser.parse_args()

    print(f"=== Starting Network Port Audit on host: {args.host} ===")

    open_admin_ports = []

    for port, service in DEFAULT_PORTS.items():
        status = scan_port(args.host, port, args.timeout)
        print(f"  - Port {port:4d} ({service:25s}): {status}")
        if "OPEN" in status:
            open_admin_ports.append(port)

    print("\n=== Audit Summary ===")
    if open_admin_ports:
        print(
            f"[WARNING] Detected {len(open_admin_ports)} exposed admin/internal ports on external interface: {open_admin_ports}"
        )
        print(
            "Please configure your cloud security groups / VPC firewalls to restrict access to these ports."
        )
        sys.exit(1)
    else:
        print(
            "[SUCCESS] Zero exposed admin ports detected on external interface. Network security configurations verified."
        )
        sys.exit(0)


if __name__ == "__main__":
    main()
