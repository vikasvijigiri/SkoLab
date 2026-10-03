"""Validate aggregate connection capacity, including rolling-deploy overlap."""

from __future__ import annotations

import os
from collections.abc import Mapping


def connection_budget(env: Mapping[str, str]) -> tuple[int, int]:
    def number(key: str, default: int, minimum: int = 0) -> int:
        value = int(env.get(key, str(default)))
        if value < minimum:
            raise ValueError(f"{key} must be at least {minimum}")
        return value

    go_max = number("DB_MAX_CONNS", 15, 1)
    go_min = number("DB_MIN_CONNS", 3)
    if go_min > go_max:
        raise ValueError("DB_MIN_CONNS must not exceed DB_MAX_CONNS")
    python_max = number("DB_POOL_SIZE", 5, 1) + number("DB_MAX_OVERFLOW", 10)
    workers = number("WEB_CONCURRENCY", 1, 1)
    instances = number("APP_MAX_INSTANCES", 1, 1)
    reserved = number("DB_RESERVED_CONNECTIONS", 5, 1)
    needed = instances * (go_max + workers * python_max) + reserved
    budget = number("DB_CONNECTION_BUDGET", 0)
    if env.get("SHARED_STATE_REQUIRED", "").lower() == "true" and not budget:
        raise ValueError("Scaled deployment requires the verified DB_CONNECTION_BUDGET")
    if budget and needed > budget:
        raise ValueError(
            f"Connection ceiling {needed} exceeds DB_CONNECTION_BUDGET={budget}"
        )
    return needed, budget


if __name__ == "__main__":
    needed, budget = connection_budget(os.environ)
    print(
        f"[capacity] maximum connections including reserve: {needed}; budget: {budget or 'unset'}"
    )
