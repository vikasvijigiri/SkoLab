import time
import os
import logging
import urllib.request
import json

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("skolab.synthetic")

HEALTH_URL = os.environ.get("HEALTH_URL", "http://localhost:8000/health")
CHECK_INTERVAL_SEC = 60

def run_probe():
    try:
        req = urllib.request.Request(HEALTH_URL)
        # Set dummy SkoLab user agent to pass scraper check if needed
        req.add_header("User-Agent", "SkoLabSyntheticProbe/1.0")
        
        start_time = time.perf_counter()
        with urllib.request.urlopen(req, timeout=5) as resp:
            latency_ms = int((time.perf_counter() - start_time) * 1000)
            status_code = resp.status
            body = resp.read().decode("utf-8")
            
            if status_code != 200:
                trigger_pager(f"Unhealthy HTTP Status Code: {status_code}")
                return
                
            try:
                data = json.loads(body)
                if data.get("status") != "healthy":
                    trigger_pager(f"Degraded Subsystems: {body}")
                else:
                    logger.info(f"Synthetic probe passed successfully. Latency: {latency_ms}ms")
            except Exception as e:
                trigger_pager(f"Failed to parse health response: {e}")
                
    except Exception as e:
        trigger_pager(f"Failed to connect to health endpoint: {e}")

def trigger_pager(reason):
    logger.critical(
        f"[PAGER_ALERT] Synthetic health probe failed! Reason: {reason}. "
        f"Paging on-call SRE responder..."
    )
    # Here, a production SRE pipeline would issue a PagerDuty API request
    # e.g., using PagerDuty Events API v2 / trigger webhook

def main():
    logger.info(f"Starting SkoLab Synthetic Health Probe. Monitoring: {HEALTH_URL}")
    while True:
        run_probe()
        time.sleep(CHECK_INTERVAL_SEC)

if __name__ == "__main__":
    main()
