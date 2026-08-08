import logging
import os
import re
import time
import urllib.request


def _require_http_url(url: str) -> str:
    """urlopen honours file:, ftp: and custom schemes, so a URL that arrives
    from the environment can read a local file instead of making a request.
    Only http/https are ever intended here (ruff S310)."""
    if not url.startswith(("http://", "https://")):
        raise ValueError(f"refusing non-http(s) URL: {url!r}")
    return url


logging.basicConfig(
    level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s"
)
logger = logging.getLogger("skolab.alerter")

METRICS_URL = os.environ.get("METRICS_URL", "http://localhost:8000/metrics")
ALERT_THRESHOLD = 10
CHECK_INTERVAL_SEC = 60


def get_error_count():
    try:
        with urllib.request.urlopen(  # noqa: S310 - scheme checked above
            _require_http_url(METRICS_URL), timeout=3
        ) as resp:
            content = resp.read().decode("utf-8")
            match = re.search(r"^system_errors_total\s+(\d+)", content, re.MULTILINE)
            if match:
                return int(match.group(1))
    except Exception as e:
        logger.error(f"Failed to fetch metrics from {METRICS_URL}: {e}")
    return None


def main():
    logger.info(f"Starting SkoLab Error Rate Alerter. Monitoring: {METRICS_URL}")
    history = []  # List of tuples (timestamp, error_count)

    while True:
        current_err = get_error_count()
        now = time.time()

        if current_err is not None:
            history.append((now, current_err))
            # Clean up history older than 5 minutes (300 seconds)
            history = [x for x in history if now - x[0] <= 300]

            if len(history) >= 2:
                initial_err = history[0][1]
                latest_err = history[-1][1]
                increase = latest_err - initial_err

                if increase > ALERT_THRESHOLD:
                    logger.critical(
                        f"[CRITICAL_ALERT] High error occurrences detected! "
                        f"{increase} errors in last 5 minutes. (Total errors: {latest_err}). "
                        f"Alerting active engineering channels!"
                    )

        time.sleep(CHECK_INTERVAL_SEC)


if __name__ == "__main__":
    main()
