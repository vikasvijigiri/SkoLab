import asyncio
from app.database import AsyncSessionLocal, init_db
from app.models import User

async def seed_mock_user():
    await init_db()
    async with AsyncSessionLocal() as session:
        mock_user = User(
            id="default_local_user",
            openalex_id=None,
            display_name="Local Developer",
            email="developer@localhost"
        )
        try:
            # Check if user exists
            from sqlalchemy.future import select
            stmt = select(User).where(User.id == "default_local_user")
            result = await session.execute(stmt)
            if not result.scalars().first():
                session.add(mock_user)
                await session.commit()
                print("Mock user 'default_local_user' seeded successfully.")
            else:
                print("Mock user already exists.")
        except Exception as e:
            print(f"Error seeding mock user: {e}")

if __name__ == "__main__":
    asyncio.run(seed_mock_user())
