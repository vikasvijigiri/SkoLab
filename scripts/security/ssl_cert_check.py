import argparse
import datetime
import socket
import ssl
import sys
from urllib.parse import urlparse


def check_ssl_cert(host: str, port: int = 443, timeout: float = 3.0) -> dict:
    """Connects to the host via SSL, retrieves and parses certificate information."""
    context = ssl.create_default_context()

    # We want to verify hostname and certificates
    context.check_hostname = True
    context.verify_mode = ssl.CERT_REQUIRED

    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            with context.wrap_socket(sock, server_hostname=host) as ssock:
                cert = ssock.getpeercert()
                cipher = ssock.cipher()
                return {"status": "SUCCESS", "cert": cert, "cipher": cipher}
    except Exception as e:
        return {"status": "FAIL", "error": str(e)}


def main():
    parser = argparse.ArgumentParser(
        description="Audit SSL/TLS configurations and certificate expiration dates."
    )
    parser.add_argument(
        "--domain",
        default="google.com",
        help="Target domain to audit (default: google.com)",
    )
    args = parser.parse_args()

    # Normalize host name if a full URL is passed
    host = args.domain
    if host.startswith("http://") or host.startswith("https://"):
        host = urlparse(host).hostname

    print(f"=== Starting SSL/TLS Certificate Check for: {host} ===")

    result = check_ssl_cert(host)
    if result["status"] == "FAIL":
        print(f"[ERROR] Failed to fetch SSL certificate: {result['error']}")
        sys.exit(1)

    cert = result["cert"]
    cipher = result["cipher"]

    # Parse subject/issuer
    subject = dict(x[0] for x in cert.get("subject", []))
    issuer = dict(x[0] for x in cert.get("issuer", []))

    subject_cn = subject.get("commonName", "Unknown")
    issuer_cn = issuer.get("commonName", "Unknown")

    # Parse dates
    # format: 'May 28 23:59:59 2026 GMT'
    not_after_str = cert.get("notAfter")
    not_before_str = cert.get("notBefore")

    not_after = datetime.datetime.strptime(not_after_str, "%b %d %H:%M:%S %Y %Z")

    now = datetime.datetime.now(datetime.UTC).replace(tzinfo=None)
    days_to_expire = (not_after - now).days

    print(f"  - Common Name (CN)   : {subject_cn}")
    print(f"  - Issuer             : {issuer_cn}")
    print(f"  - Valid From         : {not_before_str}")
    print(f"  - Valid Until        : {not_after_str}")
    print(
        f"  - Cipher Suite       : {cipher[0]} (Protocol: {cipher[1]} | Strength: {cipher[2]} bits)"
    )

    print("\n=== Certificate Expiration Audit ===")
    if days_to_expire < 0:
        print(
            f"[CRITICAL] Certificate has EXPIRED! (Expired {-days_to_expire} days ago)"
        )
        sys.exit(1)
    elif days_to_expire < 30:
        print(
            f"[WARNING] Certificate is expiring soon! (Expires in {days_to_expire} days)"
        )
        sys.exit(1)
    else:
        print(f"[SUCCESS] Certificate is valid. (Expires in {days_to_expire} days)")
        sys.exit(0)


if __name__ == "__main__":
    main()
