import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from fastapi.middleware.httpsredirect import HTTPSRedirectMiddleware
from app.main import app

def test_cors_configuration():
    """Verify that CORS origins are configured and wildcard is not allowed when credentials are true."""
    client = TestClient(app)
    
    # Send a request with a valid origin
    headers = {
        "Origin": "http://localhost:3000",
        "Access-Control-Request-Method": "GET"
    }
    response = client.options("/", headers=headers)
    
    # Assert headers are present
    assert response.headers.get("access-control-allow-origin") == "http://localhost:3000"
    assert response.headers.get("access-control-allow-credentials") == "true"
    
    # Send a request with an invalid/unauthorized origin
    headers_invalid = {
        "Origin": "http://malicioussite.com",
        "Access-Control-Request-Method": "GET"
    }
    response_invalid = client.options("/", headers=headers_invalid)
    
    # Unauthorized origins should not return matching CORS headers
    assert response_invalid.headers.get("access-control-allow-origin") != "http://malicioussite.com"

def test_https_redirection_middleware():
    """Verify that HTTPSRedirectMiddleware redirects HTTP traffic to HTTPS with 307."""
    test_app = FastAPI()
    test_app.add_middleware(HTTPSRedirectMiddleware)
    
    @test_app.get("/secure-endpoint")
    def secure_endpoint():
        return {"message": "secure"}
        
    # Standard http test client request
    client = TestClient(test_app)
    
    # We disable follow_redirects to inspect the redirect response code
    response = client.get("http://testserver/secure-endpoint", follow_redirects=False)
    
    assert response.status_code == 307
    assert response.headers.get("location") == "https://testserver/secure-endpoint"
