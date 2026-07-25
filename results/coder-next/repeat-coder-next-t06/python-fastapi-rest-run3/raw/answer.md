### FILE: app/__init__.py
```python
from .main import app, create_app

__all__ = ['app', 'create_app']
```

### FILE: app/main.py
```python
from typing import List, Optional
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

class State:
    def __init__(self):
        self.tasks: dict[int, Task] = {}
        self.next_id: int = 1

def create_app() -> FastAPI:
    app = FastAPI()
    state = State()
    
    @app.get("/health")
    async def health():
        return {"status": "ok"}
    
    @app.get("/tasks")
    async def get_tasks():
        return [state.tasks[tid] for tid in sorted(state.tasks.keys())]
    
    @app.post("/tasks", status_code=status.HTTP_201_CREATED)
    async def create_task(task_create: TaskCreate):
        task_id = state.next_id
        state.next_id += 1
        task = Task(id=task_id, title=task_create.title, done=False)
        state.tasks[task_id] = task
        return task
    
    @app.get("/tasks/{task_id}")
    async def get_task(task_id: int):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        return state.tasks[task_id]
    
    @app.put("/tasks/{task_id}")
    async def update_task(task_id: int, task_update: TaskUpdate):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        task = Task(id=task_id, title=task_update.title, done=task_update.done)
        state.tasks[task_id] = task
        return task
    
    @app.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_task(task_id: int):
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
    client.post("/tasks", json={"title": "Test task"})
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

def test_delete_existing_task_returns_204_and_subsequent_get_returns_404():
    client = TestClient(create_app())
    # Create a task first
    client.post("/tasks", json={"title": "Task to delete"})
    
    # Delete it
    response = client.delete("/tasks/1")
    assert response.status_code == 204
    
    # Verify it's gone
    response = client.get("/tasks/1")
    assert response.status_code == 404
```