from fastapi.testclient import TestClient
from app.main import create_app

def test_health():
    client = TestClient(create_app())
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_create_task():
    client = TestClient(create_app())
    response = client.post("/tasks", json={"title": "Test task"})
    assert response.status_code == 201
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Test task"
    assert data["done"] is False

def test_get_task_after_creation():
    client = TestClient(create_app())
    create_response = client.post("/tasks", json={"title": "Test task"})
    task_id = create_response.json()["id"]
    
    response = client.get(f"/tasks/{task_id}")
    assert response.status_code == 200
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Test task"
    assert data["done"] is False

def test_get_nonexistent_task():
    client = TestClient(create_app())
    response = client.get("/tasks/999")
    assert response.status_code == 404

def test_delete_task_and_verify():
    client = TestClient(create_app())
    
    # Create a task first
    client.post("/tasks", json={"title": "To delete"})
    
    # Delete it
    response = client.delete("/tasks/1")
    assert response.status_code == 204
    
    # Verify it's gone
    response = client.get("/tasks/1")
    assert response.status_code == 404
