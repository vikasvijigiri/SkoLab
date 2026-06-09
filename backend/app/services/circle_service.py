import logging
from datetime import datetime, timezone
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user_models import UserCircle, User, ResearcherProfile

logger = logging.getLogger("skolab.circle")


def _utcnow():
    return datetime.now(timezone.utc).replace(tzinfo=None)


class CircleService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def get_circle(self, user_id: str) -> list[dict]:
        stmt = (
            select(UserCircle, User.display_name)
            .join(User, User.id == UserCircle.peer_id)
            .where(UserCircle.user_id == user_id)
            .order_by(UserCircle.relevance_score.desc(), UserCircle.last_interacted.desc())
        )
        rows = (await self.db.execute(stmt)).all()
        return [
            {
                "peer_id": r.UserCircle.peer_id,
                "peer_name": r.display_name,
                "relationship_type": r.UserCircle.relationship_type,
                "field_tags": r.UserCircle.field_tags or [],
                "spark_sessions_count": r.UserCircle.spark_sessions_count,
                "relevance_score": r.UserCircle.relevance_score,
                "last_interacted": r.UserCircle.last_interacted.isoformat() if r.UserCircle.last_interacted else None,
            }
            for r in rows
        ]

    async def add_to_circle(
        self,
        user_id: str,
        peer_id: str,
        relationship_type: str = "manual",
        field_tags: list[str] | None = None,
        relevance_score: float = 0.5,
    ) -> dict:
        existing = await self.db.execute(
            select(UserCircle).where(
                UserCircle.user_id == user_id, UserCircle.peer_id == peer_id
            )
        )
        entry = existing.scalar_one_or_none()

        if entry:
            entry.relationship_type = relationship_type
            if field_tags is not None:
                entry.field_tags = field_tags
            entry.relevance_score = max(entry.relevance_score, relevance_score)
            entry.last_interacted = _utcnow()
        else:
            entry = UserCircle(
                user_id=user_id,
                peer_id=peer_id,
                relationship_type=relationship_type,
                field_tags=field_tags or [],
                relevance_score=relevance_score,
                last_interacted=_utcnow(),
            )
            self.db.add(entry)

        await self.db.commit()
        return {"user_id": user_id, "peer_id": peer_id, "status": "ok"}

    async def remove_from_circle(self, user_id: str, peer_id: str) -> dict:
        await self.db.execute(
            delete(UserCircle).where(
                UserCircle.user_id == user_id, UserCircle.peer_id == peer_id
            )
        )
        await self.db.commit()
        return {"user_id": user_id, "peer_id": peer_id, "status": "removed"}

    async def record_spark_session(self, seeker_id: str, helper_id: str, field_tags: list[str]) -> None:
        """
        Called after a completed Spark session to strengthen both users' circles
        and bump their spark_sessions_count.
        """
        for user_id, peer_id in [(seeker_id, helper_id), (helper_id, seeker_id)]:
            existing = await self.db.execute(
                select(UserCircle).where(
                    UserCircle.user_id == user_id, UserCircle.peer_id == peer_id
                )
            )
            entry = existing.scalar_one_or_none()
            if entry:
                entry.spark_sessions_count += 1
                entry.relevance_score = min(1.0, entry.relevance_score + 0.1)
                entry.last_interacted = _utcnow()
                if field_tags:
                    merged = list(set((entry.field_tags or []) + field_tags))
                    entry.field_tags = merged
            else:
                score = min(1.0, 0.5 + 0.1 * len(field_tags))
                self.db.add(UserCircle(
                    user_id=user_id,
                    peer_id=peer_id,
                    relationship_type="spark_session",
                    field_tags=field_tags,
                    spark_sessions_count=1,
                    relevance_score=score,
                    last_interacted=_utcnow(),
                ))

        try:
            await self.db.commit()
        except Exception:
            await self.db.rollback()
            raise

    async def find_field_matches(
        self, user_id: str, tags: list[str], limit: int = 20
    ) -> list[dict]:
        """
        Returns SkoLab users whose ResearcherProfile concepts overlap with the
        given tags, excluding the requesting user. Used to seed the Spark broadcast.
        """
        if not tags:
            return []

        stmt = select(ResearcherProfile).where(ResearcherProfile.concepts.isnot(None))
        rows = (await self.db.execute(stmt)).scalars().all()

        matches = []
        tags_lower = [t.lower() for t in tags]
        for profile in rows:
            concepts = [c.lower() for c in (profile.concepts or [])]
            overlap = sum(1 for tag in tags_lower if any(tag in c or c in tag for c in concepts))
            if overlap > 0:
                matches.append({
                    "openalex_id": profile.openalex_id,
                    "display_name": profile.display_name,
                    "institution": profile.institution,
                    "field_of_study": profile.field_of_study,
                    "overlap_count": overlap,
                })

        matches.sort(key=lambda x: x["overlap_count"], reverse=True)
        return matches[:limit]
