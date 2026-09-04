"""Thin composed facade over the per-feature pipeline mixins.

`PipelineServices` is assembled from the mixins in
`app.services.platform.pipeline` so every existing
`from app.services.platform.pipeline_services import PipelineServices` import
and call site keeps working unchanged. See `app/services/platform/pipeline/`
for the individual feature modules and
`docs/plans/2026-09-03-split-pipeline-services.md` for the split rationale.
"""

from app.services.platform.pipeline.author_chat import AuthorChatMixin
from app.services.platform.pipeline.base import _PipelineBase
from app.services.platform.pipeline.feed import FeedMixin
from app.services.platform.pipeline.grants import GrantsMixin
from app.services.platform.pipeline.journals import JournalsMixin
from app.services.platform.pipeline.network import NetworkMixin
from app.services.platform.pipeline.synergy import SynergyMixin

# Re-exported so existing `from ...pipeline_services import <name>` paths resolve.
from app.services.platform.pipeline.text_utils import (
    extract_metadata_from_abstract,
    is_field_semantically_relevant,
    is_prestigious_journal,
)

__all__ = [
    "PipelineServices",
    "extract_metadata_from_abstract",
    "is_field_semantically_relevant",
    "is_prestigious_journal",
]


class PipelineServices(
    FeedMixin,
    GrantsMixin,
    SynergyMixin,
    JournalsMixin,
    NetworkMixin,
    AuthorChatMixin,
    _PipelineBase,
):
    """Composed facade — implementations live in the mixin modules."""

    pass
