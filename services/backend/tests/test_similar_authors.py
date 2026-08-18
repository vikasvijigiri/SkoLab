"""
test_similar_authors.py
=======================
Tests for derive_similar_authors_from_works (app/services/data/openalex_service.py),
the shared helper behind the three similar-researcher surfaces: the author
profile page (api/v1/endpoints/authors.py), the Roadmap peer panel
(api/v1/endpoints/feed.py), and the daily-feed peer-widening channel
(services/platform/pipeline_services.py::_find_similar_researchers).

Runs fully offline — no OpenAlex/arXiv network calls; every work dict is
hand-built to match a real payload shape.

The malformed-shape cases exist because the daily-feed call site feeds this
helper a *mixed* candidate pool (arXiv-derived dicts + OpenAlex works +
related-works), unlike the other two call sites which pass pure
search_works() output. See decisions/0005-similar-researchers-via-authorship.md.
"""

from app.services.data.openalex_service import derive_similar_authors_from_works


# ──────────────────────────────────────────────────────────────────────────────
# Fixtures
# ──────────────────────────────────────────────────────────────────────────────


def _openalex_work(author_id: str, name: str, institutions=("MIT",)):
    """A work shaped the way OpenAlex actually returns one."""
    insts = [{"display_name": i} for i in institutions] if institutions else []
    return {
        "authorships": [
            {
                "author": {
                    "id": f"https://openalex.org/{author_id}",
                    "display_name": name,
                },
                "institutions": insts,
            }
        ]
    }


def _arxiv_work(name: str):
    """
    A work shaped the way _fetch_arxiv_candidates builds one: an `author`
    sub-dict carrying only display_name, with no `id` and no `institutions`.
    """
    return {"authorships": [{"author": {"display_name": name}}]}


# ──────────────────────────────────────────────────────────────────────────────
# Happy path
# ──────────────────────────────────────────────────────────────────────────────


def test_extracts_id_name_and_institution():
    out = derive_similar_authors_from_works(
        [_openalex_work("A123", "Ann Lee")], exclude_author_id=None, limit=4
    )
    assert out == [{"id": "A123", "display_name": "Ann Lee", "institution": "MIT"}]


def test_excludes_the_requesting_author():
    works = [_openalex_work("A123", "Ann Lee"), _openalex_work("A999", "Bo Chen")]
    out = derive_similar_authors_from_works(
        works, exclude_author_id="https://openalex.org/A123", limit=4
    )
    assert [c["id"] for c in out] == ["A999"]


def test_deduplicates_repeat_authors_and_honours_limit():
    works = [_openalex_work(f"A{i}", f"Author {i}") for i in range(10)]
    works.append(_openalex_work("A0", "Author 0"))  # duplicate
    out = derive_similar_authors_from_works(works, exclude_author_id=None, limit=3)
    assert len(out) == 3
    assert len({c["id"] for c in out}) == 3


def test_arxiv_shaped_authorships_are_skipped_not_crashed():
    """
    arXiv candidates carry no author id, so they cannot become peers -- but
    they must be skipped silently rather than raising, because the daily-feed
    call site passes them in the same list as real OpenAlex works.
    """
    out = derive_similar_authors_from_works(
        [_arxiv_work("Jane Doe")], exclude_author_id=None, limit=4
    )
    assert out == []


# ──────────────────────────────────────────────────────────────────────────────
# Malformed shapes -- must degrade, never raise
# ──────────────────────────────────────────────────────────────────────────────


def test_null_institutions_yields_none_not_crash():
    work = {
        "authorships": [
            {
                "author": {"id": "https://openalex.org/A8", "display_name": "Cy"},
                "institutions": None,
            }
        ]
    }
    out = derive_similar_authors_from_works(work and [work], None, limit=4)
    assert out == [{"id": "A8", "display_name": "Cy", "institution": None}]


def test_non_dict_institution_entry_does_not_raise():
    """A string where an institution object was expected must not kill the call."""
    work = {
        "authorships": [
            {
                "author": {"id": "https://openalex.org/A9", "display_name": "Bob"},
                "institutions": ["MIT"],  # string, not {"display_name": ...}
            }
        ]
    }
    out = derive_similar_authors_from_works([work], None, limit=4)
    assert out == [{"id": "A9", "display_name": "Bob", "institution": None}]


def test_null_authorship_entry_does_not_raise():
    out = derive_similar_authors_from_works([{"authorships": [None]}], None, limit=4)
    assert out == []


def test_non_dict_author_entry_does_not_raise():
    out = derive_similar_authors_from_works(
        [{"authorships": [{"author": "Jane Doe"}]}], None, limit=4
    )
    assert out == []


def test_non_dict_work_entry_does_not_raise():
    out = derive_similar_authors_from_works(
        ["not-a-work", _openalex_work("A5", "Real Author")], None, limit=4
    )
    assert [c["id"] for c in out] == ["A5"]


def test_malformed_entry_does_not_discard_the_good_ones_after_it():
    """
    Ordering matters: a bad entry early in the list must not prevent the
    valid authors behind it from being returned.
    """
    works = [
        {"authorships": [None]},
        _openalex_work("A7", "Good Author"),
    ]
    out = derive_similar_authors_from_works(works, None, limit=4)
    assert [c["id"] for c in out] == ["A7"]
