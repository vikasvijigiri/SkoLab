from sqlalchemy import create_engine, Column, Integer, text
from sqlalchemy.orm import declarative_base, sessionmaker
from app.db.encrypted_type import EncryptedString
import pytest
from cryptography.fernet import Fernet
from types import SimpleNamespace

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


def test_invalid_key_is_rejected_instead_of_padded(monkeypatch):
    monkeypatch.setattr("app.db.encrypted_type.settings", SimpleNamespace(database_encryption_key="weak"))
    with pytest.raises(ValueError, match="valid Fernet key"):
        EncryptedString()


@pytest.mark.parametrize("stored", ["plaintext@example.com", "not-a-fernet-token"])
def test_plaintext_and_corruption_fail_closed(stored):
    with pytest.raises(ValueError, match="could not be decrypted"):
        EncryptedString().process_result_value(stored, None)


def test_wrong_key_fails_closed_without_disclosing_content():
    ciphertext = Fernet(Fernet.generate_key()).encrypt(b"private@example.com").decode()
    with pytest.raises(ValueError, match="could not be decrypted") as exc:
        EncryptedString().process_result_value(ciphertext, None)
    assert "private@example.com" not in str(exc.value)
