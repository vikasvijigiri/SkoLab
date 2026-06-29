from pydantic import BaseModel


class Quest(BaseModel):
    id: str
    title: str
    reward_entropy: int
    is_completed: bool


class LeaderboardEntry(BaseModel):
    rank: int
    user_name: str
    institution: str
    entropy_score: int
