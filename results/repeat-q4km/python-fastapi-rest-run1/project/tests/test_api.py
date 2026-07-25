import pytest
from fastapi.testclient import TestClient
from app.main import create_app


@pytest.fixture
def client():
    return TestClient(create_app())


def test_get_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_post_task_creates_with_id_1(client):
    resp = client.post("/tasks", json={"title": "First task"})
    assert resp.status_code == 201
    data = resp.json()
    assert data["id"] == 1
    assert data["title"] == "First task"
    assert data["done"] is False


def test_get_task_after_creation(client):
    client.post("/tasks", json={"title": "Task 1"})
    resp = client.get("/tasks/1")
    assert resp.status_code == 200
    assert resp.json()["title"] == "Task 1"


def test_get_missing_task_returns_404(client):
    resp = client.get("/tasks/999")
    assert resp.status_code == 404


def test_delete_task_returns_204_and_subsequent_get_404(client):
    client.post("/tasks", json={"title": "To delete"})
    resp = client.delete("/tasks/1")
    assert resp.status_code == 204

    resp = client.get("/tasks/1")
    assert resp.status_code == 404
