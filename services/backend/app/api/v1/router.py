from fastapi import APIRouter

from app.api.v1.endpoints import ai, colab

api_router = APIRouter()

api_router.include_router(colab.router, tags=["CoLab"])
api_router.include_router(ai.router, tags=["AI"])
