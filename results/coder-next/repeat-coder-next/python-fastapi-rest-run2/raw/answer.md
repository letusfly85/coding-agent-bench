### FILE: app/__init__.py
```python
from .main import app, create_app
```

### FILE: app/main.py
```python
from typing import List
from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel

class Task(BaseModel):
    id: int
    title: str
    done: bool

class TaskCreate(BaseModel):
    title: str

class TaskUpdate(BaseModel):
    title: str
    done: bool

class AppState:
    def __init__(self):
        self.tasks: dict[int, Task] = {}
        self.next_id: int = 1

def create_app() -> FastAPI:
    app = FastAPI()
    state = AppState()
    
    @app.get("/health")
    def health():
        return {"status": "ok"}
    
    @app.get("/tasks")
    def get_tasks():
        return [state.tasks[id_] for id_ in sorted(state.tasks.keys())]
    
    @app.post("/tasks", status_code=status.HTTP_201_CREATED)
    def create_task(task_create: TaskCreate):
        task_id = state.next_id
        state.next_id += 1
        task = Task(id=task_id, title=task_create.title, done=False)
        state.tasks[task_id] = task
        return task
    
    @app.get("/tasks/{task_id}")
    def get_task(task_id: int):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        return state.tasks[task_id]
    
    @app.put("/tasks/{task_id}")
    def update_task(task_id: int, task_update: TaskUpdate):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        updated_task = Task(id=task_id, title=task_update.title, done=task_update.done)
        state.tasks[task_id] = updated_task
        return updated_task
    
    @app.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
    def delete_task(task_id: int):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        del state.tasks[task_id]
        return None
    
    return app

app = create_app()
```

### FILE: tests/test_api.py
```python
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
```