"""
app/db/blind_index.py
=====================
Deterministic blind index for equality lookups on the Fernet-encrypted
``users.email`` column.

``EncryptedString`` (app/db/encrypted_type.py) uses Fernet — a fresh random IV
per encryption — so ``WHERE email = :value`` and ``email ILIKE :value`` never
match. A blind index is the standard fix: store ``HMAC-SHA256(key, normalise(
email))`` in its own column (``users.email_bidx``) and match on that instead.

The key is ``EMAIL_BLIND_INDEX_KEY`` — deliberately separate from
``DATABASE_ENCRYPTION_KEY`` (different purpose, different rotation story). When
it is unset, ``email_blind_index`` returns ``None`` and callers simply skip
email-equality matching rather than failing.

Normalisation (``strip().lower()``) is duplicated in the Go gateway
(``internal/recommendation``); keep the two in lock-step or matches silently
stop working.
"""

from __future__ import annotations

import hashlib
import hmac

from app.core.config import settings


def normalise_email(email: str) -> str:
    """Canonical form used as the HMAC message. Must match the Go gateway."""
    return email.strip().lower()


def email_blind_index(email: str | None) -> str | None:
    """Hex HMAC-SHA256 of the normalised address, or ``None`` when the address
    is empty or ``EMAIL_BLIND_INDEX_KEY`` is not configured."""
    if not email:
        return None
    key = settings.email_blind_index_key
    if not key:
        return None
    return hmac.new(
        key.encode("utf-8"),
        normalise_email(email).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
