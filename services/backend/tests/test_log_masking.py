"""PII masking must hide personal data without destroying operational data."""

import pytest

from app.main import mask_pii


@pytest.mark.parametrize(
    "text",
    [
        "call +1 415 555 0132 now",
        "call +44 20 7946 0958",
        "call +919876543210",
        "call (415) 555-0132",
        "call 415-555-0132.",
        "call 415.555.0132",
    ],
)
def test_phone_numbers_are_masked(text):
    masked = mask_pii(text)
    assert "[MASKED_PHONE]" in masked
    assert "555" not in masked and "7946" not in masked and "98765" not in masked


@pytest.mark.parametrize(
    "text",
    [
        '127.0.0.1:8000 - "GET /readyz HTTP/1.1" 200',
        "10.20.30.40:54321",
        "colab.compile user=3f2a9b status=compiled ms=15321 src_bytes=48213",
        "timestamp 2026-10-08T02:07:43.123456Z epoch 1791424870743",
        "workspace 734d92c8-b275-4181-ad05-07a0e8b62b79",
        "request_id b09eb62f3ef240f8b8ff32eb3b30eced",
        "/api/v1/workspaces?page_size=100&n=12345678",
    ],
)
def test_operational_data_survives(text):
    assert mask_pii(text) == text


def test_emails_and_bearer_tokens_are_masked():
    masked = mask_pii("ada@example.org sent Authorization: Bearer eyJ0eXAi.12345678.abc-_=")
    assert masked == "[MASKED_EMAIL] sent Authorization: Bearer [MASKED_TOKEN]"


def test_non_strings_pass_through():
    assert mask_pii(None) is None
