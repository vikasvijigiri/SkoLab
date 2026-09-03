"""Blind index for users.email — determinism, normalisation, and cross-language
parity with the Go gateway's emailBlindIndex (internal/recommendation)."""

from types import SimpleNamespace

import pytest

from app.db import blind_index


@pytest.fixture
def keyed(monkeypatch):
    """Point the helper at a fixed key (settings is a frozen dataclass)."""
    monkeypatch.setattr(
        blind_index, "settings", SimpleNamespace(email_blind_index_key="testkey")
    )


# Same vectors are asserted in
# services/backend-go/internal/recommendation/recommendation_test.go.
_VECTORS = {
    "Test@Example.com ": "b300f80f54e9a5036e698a89ee6da477376e60459b5040da7ae2e3b8320a9ec9",
    "test@example.com": "b300f80f54e9a5036e698a89ee6da477376e60459b5040da7ae2e3b8320a9ec9",
    "  ALICE@Foo.ORG": "9788dfc7dc19fc0457a026c5dee1fecb7308228f2cb6988b61aaf1dd02204723",
}


@pytest.mark.parametrize("raw,expected", list(_VECTORS.items()))
def test_known_vectors_and_normalisation(keyed, raw, expected):
    assert blind_index.email_blind_index(raw) == expected


def test_case_and_whitespace_collapse_to_one_index(keyed):
    a = blind_index.email_blind_index("Test@Example.com ")
    b = blind_index.email_blind_index("test@example.com")
    assert a == b


def test_none_when_key_unset(monkeypatch):
    monkeypatch.setattr(
        blind_index, "settings", SimpleNamespace(email_blind_index_key="")
    )
    assert blind_index.email_blind_index("a@b.com") is None


def test_none_for_empty_address(keyed):
    assert blind_index.email_blind_index("") is None
    assert blind_index.email_blind_index(None) is None


def test_user_orm_write_sets_email_bidx(keyed):
    from app.models.user_models import User

    u = User(id="u1", display_name="U One", email="Test@Example.com")
    assert u.email_bidx == _VECTORS["test@example.com"]

    u.email = None
    assert u.email_bidx is None
