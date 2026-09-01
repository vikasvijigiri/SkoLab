"""
Recommendation Service — service.py
=====================================
Peer/collaborator autocomplete for CoLab project invites (registered-user
search, invite logging, registered-contact checks). Live, used by both
Android and web.

Previously also served a unified paper/grant/collaborator recommendation
endpoint (`get_unified_recommendations`) — retired as dead code, never
wired to a frontend caller and superseded in quality by the embeddings-based
daily-feed path. See `decisions/0007-retire-dormant-unified-recommendations.md`.
"""

from typing import List, Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.domains.recommendation.schemas import (
    PeerRecommendation,
    PeerInviteLogRequest,
    RegisteredCheckRequest,
    RegisteredCheckResponse,
)


class RecommendationService:
    def __init__(self, db: AsyncSession):
        self.db = db

    # ── Peer Recommendations & Autocomplete (FAANG-style Data Management) ───

    async def get_peer_recommendations(
        self, query: str, user_id: Optional[str] = None
    ) -> List[PeerRecommendation]:
        from app.models.user_models import User, ResearcherProfile, UserCircle
        from sqlalchemy import select, or_, text

        q = f"%{query.strip().lower()}%"
        # 1. Fetch matching registered users from Postgres
        stmt_users = (
            select(User)
            .where(
                or_(
                    User.display_name.ilike(q),
                    User.email.ilike(q),
                    text("users.username ILIKE :q").bindparams(q=q),
                    text("users.phone ILIKE :q").bindparams(q=q),
                )
            )
            .limit(20)
        )

        res_users = await self.db.execute(stmt_users)
        db_users = res_users.scalars().all()

        # 2. Get user's own profile to compare research focus
        user_focus = ""
        user_circle_peer_ids = set()
        if user_id:
            user_stmt = select(User).where(User.id == user_id)
            user_res = await self.db.execute(user_stmt)
            curr_user = user_res.scalar_one_or_none()
            if curr_user:
                user_focus = getattr(curr_user, "research_focus", "") or ""
            # Get circles
            circle_stmt = select(UserCircle.peer_id).where(
                UserCircle.user_id == user_id
            )
            circle_res = await self.db.execute(circle_stmt)
            user_circle_peer_ids = set(circle_res.scalars().all())

        suggestions = []
        for u in db_users:
            uname = getattr(u, "username", "") or ""
            uphone = getattr(u, "phone", "") or ""
            ufocus = getattr(u, "research_focus", "") or ""

            # Base relevance
            score = 0.5
            if (
                query.lower() == uname.lower()
                or query.lower() == (u.email or "").lower()
                or query.lower() == uphone
            ):
                score += 0.4
            elif (
                query.lower() in uname.lower()
                or query.lower() in (u.email or "").lower()
                or query.lower() in uphone
            ):
                score += 0.3
            elif query.lower() in u.display_name.lower():
                score += 0.2

            if user_focus and ufocus:
                words_a = set(user_focus.lower().split())
                words_b = set(ufocus.lower().split())
                overlap = len(words_a & words_b)
                if overlap > 0:
                    score += min(0.15, overlap * 0.03)

            if u.id in user_circle_peer_ids:
                score += 0.1

            suggestions.append(
                PeerRecommendation(
                    uid=u.id,
                    name=u.display_name,
                    username=uname,
                    email=u.email,
                    phone=uphone,
                    research_focus=ufocus,
                    is_registered=True,
                    relevance_score=min(1.0, score),
                )
            )

        # 3. Query researcher profiles (OpenAlex cached profiles) to match unregistered scientists
        stmt_profiles = (
            select(ResearcherProfile)
            .where(
                or_(
                    ResearcherProfile.display_name.ilike(q),
                    ResearcherProfile.field_of_study.ilike(q),
                )
            )
            .limit(20)
        )

        res_profiles = await self.db.execute(stmt_profiles)
        db_profiles = res_profiles.scalars().all()

        existing_names = {s.name.lower() for s in suggestions}

        for p in db_profiles:
            if p.display_name.lower() in existing_names:
                continue

            score = 0.4
            if query.lower() in p.display_name.lower():
                score += 0.2
            if p.field_of_study and query.lower() in p.field_of_study.lower():
                score += 0.1

            if user_focus and p.field_of_study:
                words_a = set(user_focus.lower().split())
                words_b = set(p.field_of_study.lower().split())
                overlap = len(words_a & words_b)
                if overlap > 0:
                    score += min(0.1, overlap * 0.02)

            suggestions.append(
                PeerRecommendation(
                    uid=None,
                    name=p.display_name,
                    username=None,
                    email=f"{p.display_name.lower().replace(' ', '')}@university.edu",
                    phone=None,
                    research_focus=p.field_of_study,
                    is_registered=False,
                    relevance_score=min(1.0, score),
                )
            )

        suggestions.sort(key=lambda x: x.relevance_score, reverse=True)
        return suggestions[:10]

    async def log_peer_invite(self, req: PeerInviteLogRequest) -> bool:
        from app.models.user_models import User, UserCircle
        from sqlalchemy import select

        peer_id = req.peer_uid
        if not peer_id and req.peer_email:
            stmt = select(User).where(User.email == req.peer_email)
            res = await self.db.execute(stmt)
            peer = res.scalar_one_or_none()
            if peer:
                peer_id = peer.id

        if not peer_id:
            return False

        stmt_circle = select(UserCircle).where(
            UserCircle.user_id == req.user_id, UserCircle.peer_id == peer_id
        )
        res_circle = await self.db.execute(stmt_circle)
        circle = res_circle.scalar_one_or_none()

        if circle:
            circle.spark_sessions_count += 1
            circle.relevance_score = min(1.0, circle.relevance_score + 0.1)
        else:
            new_circle = UserCircle(
                user_id=req.user_id,
                peer_id=peer_id,
                relationship_type="manual",
                relevance_score=0.7,
                spark_sessions_count=1,
            )
            self.db.add(new_circle)

        await self.db.commit()
        return True

    async def check_registered_peers(
        self, req: RegisteredCheckRequest
    ) -> RegisteredCheckResponse:
        from app.models.user_models import User
        from sqlalchemy import select, or_

        emails_clean = [e.strip().lower() for e in req.emails if e.strip()]
        phones_clean = [p.strip() for p in req.phones if p.strip()]

        reg_emails = []
        reg_phones = []

        if emails_clean or phones_clean:
            conditions = []
            if emails_clean:
                conditions.append(User.email.in_(emails_clean))
            if phones_clean:
                conditions.append(User.phone.in_(phones_clean))

            stmt = select(User).where(or_(*conditions))
            res = await self.db.execute(stmt)
            users = res.scalars().all()

            for user in users:
                if user.email:
                    reg_emails.append(user.email.lower())
                if user.phone:
                    reg_phones.append(user.phone)

        return RegisteredCheckResponse(
            registered_emails=reg_emails, registered_phones=reg_phones
        )
