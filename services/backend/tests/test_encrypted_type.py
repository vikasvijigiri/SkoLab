from sqlalchemy import create_engine, Column, Integer, text
from sqlalchemy.orm import declarative_base, sessionmaker
from app.db.encrypted_type import EncryptedString

Base = declarative_base()


class DBUser(Base):
    __tablename__ = "db_users"
    id = Column(Integer, primary_key=True)
    email = Column(EncryptedString)


def test_encrypted_string_lifecycle():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()

    # 1. Insert a test user with a plaintext email
    test_email = "test_user@example.com"
    user = DBUser(id=1, email=test_email)
    session.add(user)
    session.commit()

    # 2. Fetch the user using the ORM and verify transparent decryption
    session.expire_all()
    fetched_user = session.query(DBUser).filter(DBUser.id == 1).first()
    assert fetched_user.email == test_email

    # 3. Query the DB directly via a connection using raw text SQL
    with engine.connect() as conn:
        result = conn.execute(text("select * from db_users")).fetchone()
        db_id, db_email = result
        assert db_id == 1
        # The stored email must be encrypted, so it should not equal the original email
        assert db_email != test_email

        # Verify it can be decrypted using the Fernet instance in EncryptedString
        col_type = DBUser.email.type
        decrypted_val = col_type.fernet.decrypt(db_email.encode("utf-8")).decode(
            "utf-8"
        )
        assert decrypted_val == test_email

    session.close()
