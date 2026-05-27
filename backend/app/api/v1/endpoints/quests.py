from typing import List
from fastapi import APIRouter, Depends, Query, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select
from sqlalchemy.orm.attributes import flag_modified

from app.schemas.core import Quest, LeaderboardEntry
from app.api.dependencies import get_db
from app.models.user_models import UserPreference, User

try:
    from app.services.researcher_worker import FIRESTORE_AVAILABLE
except ImportError:
    FIRESTORE_AVAILABLE = False

if FIRESTORE_AVAILABLE:
    from firebase_admin import firestore

router = APIRouter()

@router.get("/users/quests", response_model=List[Quest])
async def get_user_quests(
    user_id: str = Query(..., description="The user ID"),
    session: AsyncSession = Depends(get_db)
):
    # Check if quests preference exists for the user
    stmt = select(UserPreference).where(
        UserPreference.user_id == user_id,
        UserPreference.preference_key == "quests"
    )
    result = await session.execute(stmt)
    pref = result.scalars().first()
    
    default_quests = [
        {"id": "discovery", "title": "Review 5 papers", "reward_entropy": 15, "is_completed": False},
        {"id": "logic", "title": "Solve a Conjecture", "reward_entropy": 50, "is_completed": False},
        {"id": "profile", "title": "Endorse a Colleague", "reward_entropy": 10, "is_completed": False}
    ]
    
    if not pref:
        # Let's ensure the user exists first
        user_stmt = select(User).where(User.id == user_id)
        user_result = await session.execute(user_stmt)
        user = user_result.scalars().first()
        if not user:
            user = User(id=user_id, display_name=f"User_{user_id[:8]}" if len(user_id) > 8 else user_id)
            session.add(user)
            await session.flush()
            
        pref = UserPreference(
            user_id=user_id,
            preference_key="quests",
            preference_value=default_quests
        )
        session.add(pref)
        await session.commit()
        quests_data = default_quests
    else:
        quests_data = pref.preference_value or default_quests
        
    return [Quest(**q) for q in quests_data]

@router.post("/users/quests/complete")
async def complete_quest(
    user_id: str = Query(...),
    quest_id: str = Query(...),
    session: AsyncSession = Depends(get_db)
):
    stmt = select(UserPreference).where(
        UserPreference.user_id == user_id,
        UserPreference.preference_key == "quests"
    )
    result = await session.execute(stmt)
    pref = result.scalars().first()
    
    if not pref:
        # Pass the current session to ensure the same transaction/connection is used
        await get_user_quests(user_id=user_id, session=session)
        result = await session.execute(stmt)
        pref = result.scalars().first()
        
    if pref:
        quests = list(pref.preference_value) if pref.preference_value else []
        updated = False
        reward = 0
        for q in quests:
            if q["id"] == quest_id:
                q["is_completed"] = True
                reward = q["reward_entropy"]
                updated = True
                break
        if updated:
            pref.preference_value = quests
            flag_modified(pref, "preference_value")
            await session.commit()
            return {"status": "success", "message": f"Quest {quest_id} completed", "entropy_awarded": reward}
            
    return {"status": "error", "message": f"Quest {quest_id} not found or initialization failed"}

@router.get("/leaderboard/{field}", response_model=List[LeaderboardEntry])
async def get_leaderboard(field: str):
    try:
        if FIRESTORE_AVAILABLE:
            db = firestore.client()
            from google.cloud.firestore_v1.base_query import FieldFilter
            
            # Query Firestore for top researchers in the field
            query_ref = db.collection("global_researchers")
            if field and field != "All Fields" and field != "all":
                query_ref = query_ref.where(filter=FieldFilter("field_of_study", "==", field))
            
            docs = query_ref.order_by("innovation_score", direction=firestore.Query.DESCENDING).limit(10).stream()
            
            results = []
            for idx, doc in enumerate(docs):
                d = doc.to_dict()
                results.append(
                     LeaderboardEntry(
                         rank=idx + 1,
                         user_name=d.get("display_name") or "Unknown Researcher",
                         institution=d.get("current_institution") or "Independent",
                         entropy_score=int(d.get("innovation_score") or 0)
                     )
                )
            if results:
                return results
    except Exception as e:
        print(f"[Leaderboard] Error fetching real data: {e}", flush=True)
    
    return []
