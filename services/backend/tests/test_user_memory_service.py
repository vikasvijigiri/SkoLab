import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from app.services.user.user_memory_service import UserMemoryService
from app.schemas.core import UserMemoryEventsRequest, ActivityEventPayload
from app.models.analytics_models import UserActivityLog


@pytest.fixture
def mock_db():
    session = AsyncMock()
    session.add_all = MagicMock()
    return session


@pytest.mark.asyncio
@patch("app.services.user.user_memory_service._user_memory_cache")
async def test_sync_events_success(mock_cache, mock_db):
    mock_cache.delete = AsyncMock()

    # Construct request payload
    events = [
        ActivityEventPayload(
            type="PAPER_OPENED",
            timestamp=1685836800000,  # UTC 2023-06-04 00:00:00
            paperTitle="Superconductivity in Quantum Crystals",
            paperDomain="Physics",
            paperJournal="Nature Physics",
        ),
        ActivityEventPayload(
            type="SEARCH_EXECUTED",
            timestamp=1685840400000,  # UTC 2023-06-04 01:00:00
            query="quantum computing",
        ),
    ]
    req = UserMemoryEventsRequest(user_id="user_abc", events=events)

    service = UserMemoryService(mock_db)
    count = await service.sync_events(req)

    assert count == 2
    assert mock_db.add_all.call_count == 1
    mock_db.commit.assert_called_once()
    mock_cache.delete.assert_called_once_with("user_abc")

    # Check what was added
    added_logs = mock_db.add_all.call_args[0][0]
    assert len(added_logs) == 2
    assert added_logs[0].user_id == "user_abc"
    assert added_logs[0].event_type == "PAPER_OPENED"
    assert added_logs[0].entity_id == "Superconductivity in Quantum Crystals"
    assert added_logs[0].entity_name == "Physics"
    assert added_logs[1].entity_id == "quantum computing"


@pytest.mark.asyncio
@patch("app.services.user.user_memory_service._user_memory_cache")
async def test_get_user_memory_cache_hit(mock_cache, mock_db):
    cached_profile = {
        "user_id": "user_abc",
        "top_topics": ["Physics"],
        "active_hours": [14],
        "reading_pace": "deep_reader",
        "research_style": "focused",
        "avg_read_minutes": 5.5,
        "unfinished_papers": [],
        "recently_read_papers": ["Paper X"],
        "frequent_collaborators": [],
        "frequent_search_terms": [],
        "last_active_topic": "Physics",
        "total_papers_read": 10,
        "last_updated": 1685836800000,
    }
    mock_cache.get = AsyncMock(return_value=cached_profile)

    service = UserMemoryService(mock_db)
    profile = await service.get_user_memory("user_abc")

    assert profile.user_id == "user_abc"
    assert profile.reading_pace == "deep_reader"
    assert profile.avg_read_minutes == 5.5
    assert not mock_db.execute.called


@pytest.mark.asyncio
@patch("app.services.user.user_memory_service._user_memory_cache")
async def test_get_user_memory_no_logs_fallback(mock_cache, mock_db):
    mock_cache.get = AsyncMock(return_value=None)
    mock_cache.set = AsyncMock()

    # Database returns empty list of logs
    mock_result = MagicMock()
    mock_result.scalars.return_value.all.return_value = []
    mock_db.execute.return_value = mock_result

    service = UserMemoryService(mock_db)
    profile = await service.get_user_memory("user_xyz")

    assert profile.user_id == "user_xyz"
    assert len(profile.top_topics) == 0
    assert profile.reading_pace == "unknown"


@pytest.mark.asyncio
@patch("app.services.user.user_memory_service._user_memory_cache")
async def test_get_user_memory_aggregation(mock_cache, mock_db):
    mock_cache.get = AsyncMock(return_value=None)
    mock_cache.set = AsyncMock()

    # Build multiple user activity logs to trigger all aggregation paths
    logs = [
        UserActivityLog(
            user_id="user_abc",
            event_type="PAPER_CLOSED",
            event_metadata={
                "paperTitle": "Paper A",
                "paperDomain": "Quantum Computing",
                "durationSeconds": 300,
                "hourOfDay": 14,
            },
        ),
        UserActivityLog(
            user_id="user_abc",
            event_type="PAPER_CLOSED",
            event_metadata={
                "paperTitle": "Paper B",
                "paperDomain": "Machine Learning",
                "durationSeconds": 120,
                "hourOfDay": 14,
            },
        ),
        UserActivityLog(
            user_id="user_abc",
            event_type="SEARCH_EXECUTED",
            event_metadata={"query": "quantum computing", "hourOfDay": 10},
        ),
        UserActivityLog(
            user_id="user_abc",
            event_type="COLLABORATOR_CLICKED",
            event_metadata={"collaboratorName": "Yoshua Bengio"},
        ),
        UserActivityLog(
            user_id="user_abc",
            event_type="PAPER_CLOSED",
            event_metadata={
                "paperTitle": "Paper C",
                "paperDomain": "Bioinformatics",
                "durationSeconds": 45,
                "hourOfDay": 15,
            },
        ),
    ]

    mock_result = MagicMock()
    mock_result.scalars.return_value.all.return_value = logs
    mock_db.execute.return_value = mock_result

    service = UserMemoryService(mock_db)
    profile = await service.get_user_memory("user_abc")

    # Verify aggregation
    assert profile.user_id == "user_abc"
    assert "Quantum Computing" in profile.top_topics
    assert "Machine Learning" in profile.top_topics
    assert "Bioinformatics" in profile.top_topics

    # Peak active hour (14 has 2 logs, 10 has 1 log, 15 has 1 log)
    assert profile.active_hours[0] == 14

    # Pace calculation: avg of 300, 120, 45 is 155s = 2.58 minutes -> moderate_reader
    assert profile.reading_pace == "moderate_reader"
    assert profile.avg_read_minutes == pytest.approx(2.5833, rel=1e-3)

    # 3 unique domains: Quantum Computing, Machine Learning, Bioinformatics -> focused (>=2 and <4)
    assert profile.research_style == "focused"

    # Unfinished papers: Paper C has duration 45 (between 1 and 90)
    assert "Paper C" in profile.unfinished_papers

    # Recently read: Paper A (300) and Paper B (120) have duration >= 90
    assert "Paper A" in profile.recently_read_papers
    assert "Paper B" in profile.recently_read_papers

    # Total papers read: Paper A (300 > 30) and Paper B (120 > 30) and Paper C (45 > 30) -> 3
    assert profile.total_papers_read == 3

    # Collaborator
    assert "Yoshua Bengio" in profile.frequent_collaborators

    # Verify profile was cached
    mock_cache.set.assert_called_once()
