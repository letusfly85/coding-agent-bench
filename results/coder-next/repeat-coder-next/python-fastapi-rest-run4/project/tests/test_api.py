from fastapi.testclient import TestClient
from app.main import create_app

def test_health():
    client = TestClient(create_app())
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_post_tasks_returns_201_with_id_1():
    client = TestClient(create_app())
    response = client.post("/tasks", json={"title": "Test task"})
    assert response.status_code == 201
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Test task"
    assert data["done"] == False

def test_get_tasks_after_creation():
    client = TestClient(create_app())
    client.post("/tasks", json={"title": "First task"})
    response = client.get("/tasks/1")
    assert response.status_code == 200
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "First task"
    assert data["done"] == False

def test_get_tasks_404():
    client = TestClient(create_app())
    response = client.get("/tasks/999")
    assert response.status_code == 404

def test_delete_task():
    client = TestClient(create_app())
    # Create a task first
    client.post("/tasks", json={"title": "To delete"})
    
    # Delete the task
    response = client.delete("/tasks/1")
    assert response.status_code == 204
    
    # Verify it's gone
    response = client.get("/tasks/1")
    assert response.status_code == 404
