import pytest
from unittest.mock import AsyncMock, MagicMock, patch
import json

from app.services.user.quests_service import QuestsService
from app.models.user_models import UserPreference, User
from app.models.researcher_models import ResearcherMetrics


@pytest.fixture
def mock_db():
    session = AsyncMock()
    session.add = MagicMock()
    return session


@pytest.fixture
def mock_openalex():
    return AsyncMock()


@pytest.mark.asyncio
async def test_get_user_quests_existing_preference(mock_db, mock_openalex):
    # Set up mock preference that already exists
    mock_pref = UserPreference(
        user_id="user_123",
        preference_key="quests",
        preference_value=[
            {
                "id": "q1",
                "title": "Test Quest 1",
                "reward_entropy": 30,
                "is_completed": False,
            },
            {
                "id": "q2",
                "title": "Test Quest 2",
                "reward_entropy": 50,
                "is_completed": True,
            },
        ],
    )

    # Mock database query return value
    mock_result = MagicMock()
    mock_result.scalars.return_value.first.return_value = mock_pref
    mock_db.execute.return_value = mock_result

    service = QuestsService(mock_db)
    quests = await service.get_user_quests("user_123", mock_openalex)

    assert len(quests) == 2
    assert quests[0].id == "q1"
    assert quests[0].reward_entropy == 30
    assert not quests[0].is_completed
    assert quests[1].is_completed


@pytest.mark.asyncio
@patch("app.services.user.quests_service.is_llm_working", return_value=True)
@patch("app.services.user.quests_service.LLMService")
async def test_get_user_quests_dynamic_initialization(
    mock_llm_class, mock_is_llm_working, mock_db, mock_openalex
):
    # Mock LLM query response
    mock_llm = MagicMock()
    mock_llm.query = AsyncMock()
    mock_llm_class.return_value = mock_llm

    llm_response = MagicMock()
    llm_response.content = json.dumps(
        [
            {
                "id": "dyn_q1",
                "title": "Dynamic Quest 1",
                "reward_entropy": 25,
                "is_completed": False,
            },
            {
                "id": "dyn_q2",
                "title": "Dynamic Quest 2",
                "reward_entropy": 40,
                "is_completed": False,
            },
            {
                "id": "dyn_q3",
                "title": "Dynamic Quest 3",
                "reward_entropy": 50,
                "is_completed": False,
            },
        ]
    )
    mock_llm.query.return_value = llm_response

    # Mock DB returns:
    # 1. First select (UserPreference) returns None
    # 2. Second select (User) returns None (so we create one)
    mock_result_pref = MagicMock()
    mock_result_pref.scalars.return_value.first.return_value = None

    mock_result_user = MagicMock()
    mock_result_user.scalars.return_value.first.return_value = None

    mock_db.execute.side_effect = [mock_result_pref, mock_result_user]

    service = QuestsService(mock_db)
    quests = await service.get_user_quests("new_user_456", mock_openalex)

    assert len(quests) == 3
    assert quests[0].id == "dyn_q1"
    assert quests[1].reward_entropy == 40
    assert not quests[2].is_completed

    # Verify DB actions
    assert mock_db.add.call_count == 2  # user and preference added
    mock_db.commit.assert_called_once()


@pytest.mark.asyncio
async def test_complete_quest_success(mock_db, mock_openalex):
    # Set up mock preference
    mock_pref = UserPreference(
        user_id="user_123",
        preference_key="quests",
        preference_value=[
            {
                "id": "q1",
                "title": "Test Quest 1",
                "reward_entropy": 30,
                "is_completed": False,
            }
        ],
    )

    mock_result = MagicMock()
    mock_result.scalars.return_value.first.return_value = mock_pref
    mock_db.execute.return_value = mock_result

    service = QuestsService(mock_db)
    result = await service.complete_quest("user_123", "q1", mock_openalex)

    assert result["status"] == "success"
    assert result["entropy_awarded"] == 30
    assert mock_pref.preference_value[0]["is_completed"] is True
    mock_db.commit.assert_called_once()


@pytest.mark.asyncio
async def test_complete_quest_not_found(mock_db, mock_openalex):
    mock_pref = UserPreference(
        user_id="user_123",
        preference_key="quests",
        preference_value=[
            {
                "id": "q1",
                "title": "Test Quest 1",
                "reward_entropy": 30,
                "is_completed": False,
            }
        ],
    )

    mock_result = MagicMock()
    mock_result.scalars.return_value.first.return_value = mock_pref
    mock_db.execute.return_value = mock_result

    service = QuestsService(mock_db)
    result = await service.complete_quest("user_123", "nonexistent_q", mock_openalex)

    assert result["status"] == "error"
    assert "not found" in result["message"]


@pytest.mark.asyncio
@patch("app.services.user.quests_service.FIRESTORE_AVAILABLE", True)
@patch("app.services.user.quests_service.firestore", create=True)
async def test_get_leaderboard_firestore(mock_firestore_mod, mock_db):
    mock_db_client = MagicMock()
    mock_firestore_mod.client.return_value = mock_db_client

    mock_doc1 = MagicMock()
    mock_doc1.to_dict.return_value = {
        "display_name": "Alice",
        "current_institution": "MIT",
        "innovation_score": 95,
    }
    mock_doc2 = MagicMock()
    mock_doc2.to_dict.return_value = {
        "display_name": "Bob",
        "current_institution": "Stanford",
        "innovation_score": 90,
    }

    # Mocking order_by().limit().get()
    mock_query = mock_db_client.collection.return_value.where.return_value.order_by.return_value.limit.return_value
    mock_query.get = MagicMock(return_value=[mock_doc1, mock_doc2])

    service = QuestsService(mock_db)
    leaderboard = await service.get_leaderboard("Physics")

    assert len(leaderboard) == 2
    assert leaderboard[0].user_name == "Alice"
    assert leaderboard[0].rank == 1
    assert leaderboard[0].entropy_score == 95
    assert leaderboard[1].user_name == "Bob"


@pytest.mark.asyncio
@patch("app.services.user.quests_service.FIRESTORE_AVAILABLE", False)
async def test_get_leaderboard_postgres_fallback(mock_db):
    # Mock ResearcherMetrics objects
    rm1 = ResearcherMetrics(
        display_name="Charlie", current_institution="Harvard", innovation_score=85
    )
    rm2 = ResearcherMetrics(
        display_name="Diana", current_institution="Caltech", innovation_score=80
    )

    mock_result = MagicMock()
    mock_result.scalars.return_value.all.return_value = [rm1, rm2]
    mock_db.execute.return_value = mock_result

    service = QuestsService(mock_db)
    leaderboard = await service.get_leaderboard("Chemistry")

    assert len(leaderboard) == 2
    assert leaderboard[0].user_name == "Charlie"
    assert leaderboard[0].entropy_score == 85
    assert leaderboard[1].user_name == "Diana"


@pytest.mark.asyncio
@patch("app.services.user.quests_service.is_llm_working", return_value=True)
@patch("app.services.user.quests_service.LLMService")
async def test_get_user_quests_user_has_openalex_id(
    mock_llm_class, mock_is_llm_working, mock_db, mock_openalex
):
    # Mock OpenAlex fetch_author_by_id return concepts
    mock_openalex.fetch_author_by_id = AsyncMock(
        return_value={
            "display_name": "Yoshua Bengio",
            "x_concepts": [
                {"display_name": "Deep Learning", "level": 1},
                {"display_name": "Neural Networks", "level": 2},
            ],
        }
    )

    mock_user = User(id="user_bengio", openalex_id="https://openalex.org/A1")

    # Mock LLM response
    mock_llm = MagicMock()
    mock_llm.query = AsyncMock()
    mock_llm_class.return_value = mock_llm

    llm_response = MagicMock()
    llm_response.content = json.dumps(
        [
            {
                "id": "q1",
                "title": "Deep Learning Quest",
                "reward_entropy": 30,
                "is_completed": False,
            },
            {
                "id": "q2",
                "title": "Neural Networks Quest",
                "reward_entropy": 40,
                "is_completed": False,
            },
            {
                "id": "q3",
                "title": "General Quest",
                "reward_entropy": 50,
                "is_completed": False,
            },
        ]
    )
    mock_llm.query.return_value = llm_response

    mock_result_pref = MagicMock()
    mock_result_pref.scalars.return_value.first.return_value = None
    mock_result_user = MagicMock()
    mock_result_user.scalars.return_value.first.return_value = mock_user

    mock_db.execute.side_effect = [mock_result_pref, mock_result_user]

    service = QuestsService(mock_db)
    quests = await service.get_user_quests("user_bengio", mock_openalex)

    assert len(quests) == 3
    assert quests[0].title == "Deep Learning Quest"
    mock_openalex.fetch_author_by_id.assert_called_once_with("A1")


@pytest.mark.asyncio
@patch("app.services.user.quests_service.is_llm_working", return_value=True)
@patch("app.services.user.quests_service.LLMService")
async def test_get_user_quests_llm_failure(
    mock_llm_class, mock_is_llm_working, mock_db, mock_openalex
):
    mock_llm = MagicMock()
    mock_llm.query = AsyncMock(side_effect=Exception("API limit exceeded"))
    mock_llm_class.return_value = mock_llm

    mock_result_pref = MagicMock()
    mock_result_pref.scalars.return_value.first.return_value = None
    mock_result_user = MagicMock()
    mock_result_user.scalars.return_value.first.return_value = None

    mock_db.execute.side_effect = [mock_result_pref, mock_result_user]

    service = QuestsService(mock_db)
    with pytest.raises(ValueError, match="Failed to query LLM to initialize quests"):
        await service.get_user_quests("user_fail", mock_openalex)


@pytest.mark.asyncio
@patch("app.services.user.quests_service.is_llm_working", return_value=True)
@patch("app.services.user.quests_service.LLMService")
async def test_get_user_quests_db_commit_error(
    mock_llm_class, mock_is_llm_working, mock_db, mock_openalex
):
    mock_llm = MagicMock()
    mock_llm.query = AsyncMock()
    mock_llm_class.return_value = mock_llm

    llm_response = MagicMock()
    llm_response.content = json.dumps(
        [
            {
                "id": "q1",
                "title": "Quest 1",
                "reward_entropy": 25,
                "is_completed": False,
            },
            {
                "id": "q2",
                "title": "Quest 2",
                "reward_entropy": 25,
                "is_completed": False,
            },
            {
                "id": "q3",
                "title": "Quest 3",
                "reward_entropy": 25,
                "is_completed": False,
            },
        ]
    )
    mock_llm.query.return_value = llm_response

    mock_result_pref = MagicMock()
    mock_result_pref.scalars.return_value.first.return_value = None
    mock_result_user = MagicMock()
    mock_result_user.scalars.return_value.first.return_value = None

    mock_db.execute.side_effect = [mock_result_pref, mock_result_user]
    mock_db.commit = AsyncMock(side_effect=Exception("DB write failure"))

    service = QuestsService(mock_db)
    with pytest.raises(Exception, match="DB write failure"):
        await service.get_user_quests("user_db_fail", mock_openalex)

    mock_db.rollback.assert_called_once()


@pytest.mark.asyncio
async def test_complete_quest_db_commit_error(mock_db, mock_openalex):
    mock_pref = UserPreference(
        user_id="user_123",
        preference_key="quests",
        preference_value=[
            {
                "id": "q1",
                "title": "Test Quest 1",
                "reward_entropy": 30,
                "is_completed": False,
            }
        ],
    )

    mock_result = MagicMock()
    mock_result.scalars.return_value.first.return_value = mock_pref
    mock_db.execute.return_value = mock_result
    mock_db.commit = AsyncMock(side_effect=Exception("DB write failure"))

    service = QuestsService(mock_db)
    with pytest.raises(Exception, match="DB write failure"):
        await service.complete_quest("user_123", "q1", mock_openalex)

    mock_db.rollback.assert_called_once()


@pytest.mark.asyncio
@patch("app.services.user.quests_service.FIRESTORE_AVAILABLE", False)
async def test_get_leaderboard_all_fail_raises_value_error(mock_db):
    mock_db.execute = AsyncMock(side_effect=Exception("DB failure"))

    service = QuestsService(mock_db)
    with pytest.raises(ValueError, match="No leaderboard data available"):
        await service.get_leaderboard("Physics")
