"""Authentication dependency for the isolated CoLab compile worker."""

from app.api.dependencies import get_verified_user


# Keep the CoLab import name stable while using the exact shared FastAPI
# dependency signature. An async *args/**kwargs wrapper would hide the
# HTTPBearer dependency from FastAPI's injection system.
require_firebase_user = get_verified_user
