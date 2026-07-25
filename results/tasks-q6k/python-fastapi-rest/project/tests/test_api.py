import pytest
from fastapi.testclient import TestClient
from app.main import create_app


@pytest.fixture
def client():
    app = create_app()
    return TestClient(app)


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_create_task(client):
    r = client.post("/tasks", json={"title": "First task"})
    assert r.status_code == 201
    data = r.json()
    assert data["id"] == 1
    assert data["title"] == "First task"
    assert data["done"] is False


def test_get_task_after_creation(client):
    client.post("/tasks", json={"title": "Task one"})
    r = client.get("/tasks/1")
    assert r.status_code == 200
    assert r.json()["title"] == "Task one"


def test_get_task_not_found(client):
    r = client.get("/tasks/999")
    assert r.status_code == 404


def test_delete_task(client):
    client.post("/tasks", json={"title": "To delete"})
    r = client.delete("/tasks/1")
    assert r.status_code == 204
    r = client.get("/tasks/1")
    assert r.status_code == 404
