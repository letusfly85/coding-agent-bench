### FILE: app/__init__.py
```python
from .main import app, create_app

__all__ = ["app", "create_app"]
```

### FILE: app/main.py
```python
from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel
from typing import List, Optional

class Task(BaseModel):
    id: int
    title: str
    done: bool

class TaskCreate(BaseModel):
    title: str

class TaskUpdate(BaseModel):
    title: str
    done: bool

class HealthCheck(BaseModel):
    status: str

# In-memory storage
tasks: dict[int, Task] = {}
next_id: int = 1

def create_app() -> FastAPI:
    global tasks, next_id
    # Reset state for fresh instance
    tasks = {}
    next_id = 1
    
    app = FastAPI()

    @app.get("/health", response_model=HealthCheck)
    def health():
        return {"status": "ok"}

    @app.get("/tasks", response_model=List[Task])
    def get_tasks():
        return list(tasks.values())

    @app.post("/tasks", status_code=201, response_model=Task)
    def create_task(task_create: TaskCreate):
        global next_id
        task = Task(id=next_id, title=task_create.title, done=False)
        tasks[next_id] = task
        next_id += 1
        return task

    @app.get("/tasks/{task_id}", response_model=Task)
    def get_task(task_id: int):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        return tasks[task_id]

    @app.put("/tasks/{task_id}", response_model=Task)
    def update_task(task_id: int, task_update: TaskUpdate):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        task = tasks[task_id]
        task.title = task_update.title
        task.done = task_update.done
        return task

    @app.delete("/tasks/{task_id}", status_code=204)
    def delete_task(task_id: int):
        if task_id not in tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        del tasks[task_id]
        return Response(status_code=204)

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
```