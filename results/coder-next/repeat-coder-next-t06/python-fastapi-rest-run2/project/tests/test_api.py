from fastapi.testclient import TestClient
from app.main import create_app

def test_health():
    client = TestClient(create_app())
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_post_tasks_returns_201_and_id_1():
    client = TestClient(create_app())
    response = client.post("/tasks", json={"title": "Test task"})
    assert response.status_code == 201
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Test task"
    assert data["done"] is False

def test_get_tasks_1_after_creation():
    client = TestClient(create_app())
    # Create task first
    client.post("/tasks", json={"title": "Test task"})
    # Now get task 1
    response = client.get("/tasks/1")
    assert response.status_code == 200
    data = response.json()
    assert data["id"] == 1
    assert data["title"] == "Test task"
    assert data["done"] is False

def test_get_tasks_999_returns_404():
    client = TestClient(create_app())
    response = client.get("/tasks/999")
    assert response.status_code == 404

def test_delete_existing_task_and_then_get():
    client = TestClient(create_app())
    # Create a task
    create_resp = client.post("/tasks", json={"title": "Delete me"})
    assert create_resp.status_code == 201
    task_id = create_resp.json()["id"]
    
    # Delete it
    delete_resp = client.delete(f"/tasks/{task_id}")
    assert delete_resp.status_code == 204
    
    # Try to get it again
    get_resp = client.get(f"/tasks/{task_id}")
    assert get_resp.status_code == 404
