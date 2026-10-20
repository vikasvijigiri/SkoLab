from sqlalchemy.types import TypeDecorator, String
from cryptography.fernet import Fernet
from app.core.config import settings


class EncryptedString(TypeDecorator):
    """
    SQLAlchemy column type that automatically encrypts values on write
    and decrypts them on read using Fernet (AES-128-CBC + HMAC-SHA256).
    """

    impl = String
    cache_ok = True

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        key = settings.database_encryption_key
        if isinstance(key, str):
            key = key.encode("utf-8")
        try:
            # Verify if the key is valid Fernet key (must be 32 URL-safe base64-encoded bytes)
            self.fernet = Fernet(key)
        except (ValueError, TypeError) as e:
            raise ValueError("DATABASE_ENCRYPTION_KEY must be a valid Fernet key") from e

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        # Encrypt the string
        encrypted = self.fernet.encrypt(value.encode("utf-8"))
        return encrypted.decode("utf-8")

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        try:
            # Decrypt the string
            decrypted = self.fernet.decrypt(value.encode("utf-8"))
            return decrypted.decode("utf-8")
        except Exception as e:
            # Never turn corruption, a wrong key, or legacy plaintext into a
            # successful read. The error deliberately excludes stored content.
            raise ValueError("Encrypted column could not be decrypted") from e
