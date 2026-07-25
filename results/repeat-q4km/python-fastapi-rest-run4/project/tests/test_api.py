import pytest
from fastapi.testclient import TestClient
from app.main import create_app


@pytest.fixture
def client():
    return TestClient(create_app())


def test_health_returns_200(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_post_task_returns_201_and_id_1(client):
    r = client.post("/tasks", json={"title": "First task"})
    assert r.status_code == 201
    data = r.json()
    assert data["id"] == 1
    assert data["title"] == "First task"
    assert data["done"] is False


def test_get_task_after_creation(client):
    client.post("/tasks", json={"title": "Task 1"})
    r = client.get("/tasks/1")
    assert r.status_code == 200
    assert r.json()["title"] == "Task 1"


def test_get_nonexistent_task_returns_404(client):
    r = client.get("/tasks/999")
    assert r.status_code == 404


def test_delete_task_returns_204_and_subsequent_get_404(client):
    client.post("/tasks", json={"title": "To delete"})
    r = client.delete("/tasks/1")
    assert r.status_code == 204

    r = client.get("/tasks/1")
    assert r.status_code == 404
