from fastapi.testclient import TestClient
from app.main import create_app

client = TestClient(create_app())

def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_create_task():
    response = client.post("/tasks", json={"title": "Test task"})
    assert response.status_code == 201
    task = response.json()
    assert task["id"] == 1
    assert task["title"] == "Test task"
    assert task["done"] is False

def test_get_task_after_creation():
    # Create task first
    client.post("/tasks", json={"title": "Get me"})
    
    response = client.get("/tasks/1")
    assert response.status_code == 200
    task = response.json()
    assert task["id"] == 1
    assert task["title"] == "Get me"
    assert task["done"] is False

def test_get_nonexistent_task():
    response = client.get("/tasks/999")
    assert response.status_code == 404

def test_delete_task():
    # Create a task first
    client.post("/tasks", json={"title": "To be deleted"})
    
    # Delete the task
    response = client.delete("/tasks/1")
    assert response.status_code == 204
    
    # Verify it's gone
    response = client.get("/tasks/1")
    assert response.status_code == 404
