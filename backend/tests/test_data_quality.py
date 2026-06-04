import datetime
import pytest
import re
import sys
import os
from sqlalchemy.exc import IntegrityError
from sqlalchemy import select

# Configure path to import from scripts outside backend
backend_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
project_root = os.path.abspath(os.path.join(backend_root, ".."))
if project_root not in sys.path:
    sys.path.insert(0, project_root)

from app.db.database import AsyncSessionLocal
from app.models.user_models import User, UserPreference, Connection, CacheEntry, AgentChatHistory
from app.models.researcher_models import ResearcherWork, ResearcherMetrics
from app.models.content_models import DailyFeedItem, ScrapedOpportunity
from app.models.analytics_models import UserSettings
from app.services.researcher_worker import teleport_researcher
from app.services.researcher_fetcher import PhysicsResearcherFetcher

# Dynamically import hyphenated scripts
import importlib.util
def import_hyphenated_module(module_name, filepath):
    spec = importlib.util.spec_from_file_location(module_name, filepath)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

consistency_path = os.path.join(project_root, "scripts", "data-consistency-check.py")
data_consistency_check = import_hyphenated_module("data_consistency_check", consistency_path)
run_checks = data_consistency_check.run_checks

cleanup_path = os.path.join(project_root, "scripts", "db-cleanup-retention.py")
db_cleanup_retention = import_hyphenated_module("db_cleanup_retention", cleanup_path)
offload_and_prune = db_cleanup_retention.offload_and_prune


@pytest.mark.anyio
async def test_email_validation():
    """Verify that User email validation rejects invalid/overlong formats and accepts valid ones."""
    async with AsyncSessionLocal() as db:
        # Valid email
        u1 = User(id="dq_test_u1", display_name="Valid User", email="test@skolab.open")
        assert u1.email == "test@skolab.open"

        # Invalid email format
        with pytest.raises(ValueError, match="Invalid email format"):
            User(id="dq_test_u2", display_name="Invalid User", email="invalid_email")

        # Too long email
        long_email = "a" * 250 + "@skolab.open"
        with pytest.raises(ValueError, match="Email exceeds maximum length"):
            User(id="dq_test_u3", display_name="Long User", email=long_email)


@pytest.mark.anyio
async def test_doi_validation():
    """Verify that DOI validation sanitizes correct DOIs and rejects invalid formats."""
    # Valid DOI with prefix
    rw1 = ResearcherWork(
        author_openalex_id="A1",
        work_openalex_id="W1",
        title="Paper 1",
        doi="https://doi.org/10.1038/s41586-021-03819-2"
    )
    # Checks cleaning of prefix
    assert rw1.doi == "10.1038/s41586-021-03819-2"

    # Valid raw DOI
    rw2 = ResearcherWork(
        author_openalex_id="A2",
        work_openalex_id="W2",
        title="Paper 2",
        doi="10.1103/PhysRevLett.116.061102"
    )
    assert rw2.doi == "10.1103/PhysRevLett.116.061102"

    # Invalid DOI format
    with pytest.raises(ValueError, match="Invalid DOI format"):
        ResearcherWork(
            author_openalex_id="A3",
            work_openalex_id="W3",
            title="Paper 3",
            doi="not_a_doi"
        )


@pytest.mark.anyio
async def test_expertise_sanitization():
    """Verify that expertise lists sanitize inputs and remove invalid/malicious text entries."""
    rm = ResearcherMetrics(
        openalex_id="A99",
        display_name="Researcher",
        expertise=["Quantum Physics", "AI-Safety!", "Clean-Text (v2)", "Malicious <script> tag"]
    )
    # "AI-Safety!" and "Malicious <script> tag" have invalid characters (!, <, >)
    # Valid expertise items should remain: "Quantum Physics", "Clean-Text (v2)"
    assert "Quantum Physics" in rm.expertise
    assert "Clean-Text (v2)" in rm.expertise
    assert "AI-Safety!" not in rm.expertise
    assert "Malicious <script> tag" not in rm.expertise


@pytest.mark.anyio
async def test_database_check_constraints():
    """Verify that database level check constraints reject invalid values on insert/commit."""
    async with AsyncSessionLocal() as db:
        # Create test users
        u1 = User(id="dq_user_c1", display_name="User C1")
        u2 = User(id="dq_user_c2", display_name="User C2")
        db.add_all([u1, u2])
        await db.commit()

        # Connection with invalid status
        conn = Connection(user_id="dq_user_c1", connected_user_id="dq_user_c2", status="invalid_status")
        db.add(conn)
        with pytest.raises(IntegrityError):
            await db.commit()
        await db.rollback()

        # Clean up users
        await db.delete(u1)
        await db.delete(u2)
        await db.commit()


@pytest.mark.anyio
async def test_database_unique_constraints():
    """Verify that duplicate preference keys per user are rejected by unique constraint."""
    async with AsyncSessionLocal() as db:
        # Create user
        u = User(id="dq_user_p1", display_name="User P1")
        db.add(u)
        await db.commit()

        pref1 = UserPreference(user_id="dq_user_p1", preference_key="theme", preference_value="dark")
        pref2 = UserPreference(user_id="dq_user_p1", preference_key="theme", preference_value="light")
        db.add_all([pref1, pref2])
        
        with pytest.raises(IntegrityError):
            await db.commit()
        await db.rollback()

        # Clean up user
        await db.delete(u)
        await db.commit()


@pytest.mark.anyio
async def test_data_ingest_filters_researcher_worker(monkeypatch):
    """Verify that teleport_researcher background job drops profiles missing name or institution."""
    dropped_logs = []

    # Mock logger to verify warning output
    class MockLogger:
        def warning(self, msg, *args):
            dropped_logs.append(msg % args)
        def info(self, msg, *args):
            pass
        def error(self, msg, *args):
            pass

    import app.services.researcher_worker as rw
    monkeypatch.setattr(rw, "logger", MockLogger())

    # Mock openalex fetch functions
    async def mock_fetch_author(author_id):
        if "bad_name" in author_id:
            return {"id": author_id, "display_name": "Unknown", "last_known_institutions": [{"display_name": "MIT"}]}
        elif "bad_inst" in author_id:
            return {"id": author_id, "display_name": "John Doe", "last_known_institutions": []}
        return None

    monkeypatch.setattr(rw, "_fetch_author_from_openalex", mock_fetch_author)

    await teleport_researcher("https://api.openalex.org/authors/bad_name")
    assert any("display name" in log for log in dropped_logs)

    dropped_logs.clear()
    await teleport_researcher("https://api.openalex.org/authors/bad_inst")
    assert any("institution" in log for log in dropped_logs)


@pytest.mark.anyio
async def test_data_ingest_filters_researcher_fetcher(monkeypatch):
    """Verify that PhysicsResearcherFetcher drops Semantic Scholar profiles lacking name/institution."""
    fetcher = PhysicsResearcherFetcher()

    # Mock SS query response
    class MockResponse:
        status_code = 200
        def json(self):
            return {
                "data": [
                    {
                        "authorId": "123",
                        "name": "Unknown",
                        "affiliations": ["MIT"]
                    }
                ]
            }

    monkeypatch.setattr(fetcher.client, "get", lambda *args, **kwargs: MockResponse())
    res1 = await fetcher.get_researcher_details("Some Name")
    assert res1 is None

    # Mock missing affiliations
    class MockResponseNoAff:
        status_code = 200
        def json(self):
            return {
                "data": [
                    {
                        "authorId": "123",
                        "name": "Jane Doe",
                        "affiliations": []
                    }
                ]
            }

    monkeypatch.setattr(fetcher.client, "get", lambda *args, **kwargs: MockResponseNoAff())
    res2 = await fetcher.get_researcher_details("Some Name")
    assert res2 is None


@pytest.mark.anyio
async def test_data_consistency_check_runs():
    """Verify that the data consistency checking script runs successfully without error."""
    await run_checks()


@pytest.mark.anyio
async def test_db_cleanup_retention_runs():
    """Verify that the db pruning and cleanup retention script runs successfully."""
    async with AsyncSessionLocal() as db:
        # Expired cache entry
        past = datetime.datetime.now(datetime.UTC).replace(tzinfo=None) - datetime.timedelta(days=1)
        expired_cache = CacheEntry(cache_key="profile::expired_test", data={"v": {}}, expires_at=past)
        db.add(expired_cache)
        await db.commit()

    # Call the cleanup routine
    await offload_and_prune()

    # Assert cache entry was deleted
    async with AsyncSessionLocal() as db:
        res = await db.execute(select(CacheEntry).where(CacheEntry.cache_key == "profile::expired_test"))
        assert res.scalar_one_or_none() is None
