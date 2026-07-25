from fastapi.testclient import TestClient
from app.main import create_app

client = TestClient(create_app())

def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_post_tasks():
    response = client.post("/tasks", json={"title": "Test task"})
    assert response.status_code == 201
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Test task"
    assert data["done"] is False

def test_get_task_after_creation():
    # First create a task
    client.post("/tasks", json={"title": "Another task"})
    
    response = client.get("/tasks/1")
    assert response.status_code == 200
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Another task"
    assert data["done"] is False

def test_get_task_not_found():
    response = client.get("/tasks/999")
    assert response.status_code == 404

def test_delete_task():
    # First create a task
    client.post("/tasks", json={"title": "Task to delete"})
    
    # Delete it
    response = client.delete("/tasks/1")
    assert response.status_code == 204
    
    # Verify it's gone
    response = client.get("/tasks/1")
    assert response.status_code == 404
