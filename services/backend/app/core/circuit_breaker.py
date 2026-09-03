"""
app/core/circuit_breaker.py — Reusable async circuit breaker for outbound API calls.

Pattern: Closed → Open (after N consecutive failures) → Half-Open (probe after cooldown) → Closed.

Usage:
    cb = CircuitBreaker(name="openalex", failure_threshold=5, recovery_timeout=30)

    @cb.call
    async def fetch_data():
        async with httpx.AsyncClient() as c:
            return await c.get(url)
"""

import asyncio
import logging
import time
from enum import Enum
from functools import wraps
from typing import Callable, Optional

logger = logging.getLogger("skolab.circuit_breaker")


class CircuitState(Enum):
    CLOSED = "closed"  # Normal operation — all requests pass through
    OPEN = "open"  # Tripped — all requests rejected immediately
    HALF_OPEN = "half_open"  # Probing — one trial request allowed to test recovery


class CircuitBreakerOpenError(Exception):
    """Raised when a call is attempted while the circuit is OPEN."""

    def __init__(self, name: str, retry_after: float):
        self.name = name
        self.retry_after = retry_after
        super().__init__(
            f"Circuit breaker '{name}' is OPEN. Retry after {retry_after:.1f}s."
        )


class CircuitBreaker:
    """
    Async-safe token-bucket circuit breaker.

    Args:
        name: Human-readable identifier used in logs and errors.
        failure_threshold: Consecutive failures before tripping to OPEN (default: 5).
        recovery_timeout: Seconds in OPEN state before allowing one probe (default: 30).
        expected_exception: Exception type(s) that count as failures. Defaults to Exception.
    """

    def __init__(
        self,
        name: str,
        failure_threshold: int = 5,
        recovery_timeout: float = 30.0,
        expected_exception: type = Exception,
    ):
        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.expected_exception = expected_exception

        self._state = CircuitState.CLOSED
        self._failure_count = 0
        self._last_failure_time: Optional[float] = None
        self._lock = asyncio.Lock()

    @property
    def state(self) -> CircuitState:
        return self._state

    async def _transition_to_open(self):
        self._state = CircuitState.OPEN
        self._last_failure_time = time.monotonic()
        logger.error(
            f"[CircuitBreaker] '{self.name}' tripped to OPEN after "
            f"{self._failure_count} consecutive failures. "
            f"Cooldown: {self.recovery_timeout}s."
        )

    async def _transition_to_half_open(self):
        self._state = CircuitState.HALF_OPEN
        logger.warning(f"[CircuitBreaker] '{self.name}' entering HALF-OPEN probe.")

    async def _transition_to_closed(self):
        self._state = CircuitState.CLOSED
        self._failure_count = 0
        self._last_failure_time = None
        logger.info(f"[CircuitBreaker] '{self.name}' recovered — back to CLOSED.")

    async def _check_state(self):
        """Evaluate current state and transition if cooldown has elapsed."""
        async with self._lock:
            if self._state == CircuitState.OPEN:
                elapsed = time.monotonic() - (self._last_failure_time or 0)
                if elapsed >= self.recovery_timeout:
                    await self._transition_to_half_open()
                else:
                    retry_after = self.recovery_timeout - elapsed
                    raise CircuitBreakerOpenError(self.name, retry_after)

    async def _on_success(self):
        async with self._lock:
            if self._state == CircuitState.HALF_OPEN:
                await self._transition_to_closed()
            elif self._state == CircuitState.CLOSED:
                self._failure_count = 0

    async def _on_failure(self, exc: Exception):
        async with self._lock:
            self._failure_count += 1
            logger.warning(
                f"[CircuitBreaker] '{self.name}' failure "
                f"{self._failure_count}/{self.failure_threshold}: {exc}"
            )
            if self._state == CircuitState.HALF_OPEN:
                # Probe failed — stay open, reset timer
                await self._transition_to_open()
            elif self._failure_count >= self.failure_threshold:
                await self._transition_to_open()

    # ── Imperative API (for call sites that can't use the decorator, e.g. a
    #    provider-fallback loop that must skip one provider but try others) ──

    async def allow(self) -> bool:
        """True if a call may proceed now. Transitions OPEN→HALF_OPEN when the
        cooldown has elapsed; returns False while still OPEN."""
        try:
            await self._check_state()
            return True
        except CircuitBreakerOpenError:
            return False

    async def record_success(self) -> None:
        await self._on_success()

    async def record_failure(self, exc: Exception) -> None:
        await self._on_failure(exc)

    def call(self, func: Callable):
        """Decorator — wraps an async callable with circuit-breaker logic."""

        @wraps(func)
        async def wrapper(*args, **kwargs):
            await self._check_state()
            try:
                result = await func(*args, **kwargs)
                await self._on_success()
                return result
            except self.expected_exception as exc:
                await self._on_failure(exc)
                raise

        return wrapper


# ── Pre-built breakers for known external services ───────────────────────────
# Import these in services that call external APIs.

openalex_breaker = CircuitBreaker(
    name="openalex",
    failure_threshold=5,
    recovery_timeout=30.0,
)

groq_breaker = CircuitBreaker(
    name="groq",
    failure_threshold=5,
    recovery_timeout=30.0,
)

semantic_scholar_breaker = CircuitBreaker(
    name="semantic_scholar",
    failure_threshold=5,
    recovery_timeout=30.0,
)
