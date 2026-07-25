from fastapi.testclient import TestClient
from app.main import create_app

client = TestClient(create_app())

def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_post_tasks_returns_201_and_id_1():
    response = client.post("/tasks", json={"title": "Test task"})
    assert response.status_code == 201
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Test task"
    assert data["done"] is False

def test_get_tasks_1_after_creation():
    # First create a task to ensure id=1 exists
    client.post("/tasks", json={"title": "Another task"})
    
    response = client.get("/tasks/1")
    assert response.status_code == 200
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Another task"
    assert data["done"] is False

def test_get_tasks_999_returns_404():
    response = client.get("/tasks/999")
    assert response.status_code == 404

def test_delete_task_returns_204_and_subsequent_get_returns_404():
    # Create a task first
    create_response = client.post("/tasks", json={"title": "To be deleted"})
    task_id = create_response.json()["id"]
    
    # Delete it
    response = client.delete(f"/tasks/{task_id}")
    assert response.status_code == 204
    
    # Verify it's gone
    response = client.get(f"/tasks/{task_id}")
    assert response.status_code == 404
