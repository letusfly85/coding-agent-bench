### FILE: app/__init__.py
```python
from .main import app, create_app

__all__ = ['app', 'create_app']
```

### FILE: app/main.py
```python
from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel
from typing import List

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
        self.tasks: List[Task] = []
        self.next_id = 1

def create_app() -> FastAPI:
    app = FastAPI()
    state = AppState()
    
    @app.get("/health")
    async def health():
        return {"status": "ok"}
    
    @app.get("/tasks")
    async def get_tasks():
        return state.tasks
    
    @app.post("/tasks", status_code=201)
    async def create_task(task_create: TaskCreate):
        task = Task(
            id=state.next_id,
            title=task_create.title,
            done=False
        )
        state.tasks.append(task)
        state.next_id += 1
        return task
    
    @app.get("/tasks/{task_id}")
    async def get_task(task_id: int):
        for task in state.tasks:
            if task.id == task_id:
                return task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @app.put("/tasks/{task_id}")
    async def update_task(task_id: int, task_update: TaskUpdate):
        for i, task in enumerate(state.tasks):
            if task.id == task_id:
                state.tasks[i] = Task(
                    id=task_id,
                    title=task_update.title,
                    done=task_update.done
                )
                return state.tasks[i]
        raise HTTPException(status_code=404, detail="Task not found")
    
    @app.delete("/tasks/{task_id}", status_code=204)
    async def delete_task(task_id: int):
        for i, task in enumerate(state.tasks):
            if task.id == task_id:
                state.tasks.pop(i)
                return Response(status_code=204)
        raise HTTPException(status_code=404, detail="Task not found")
    
    return app

app = create_app()
```

### FILE: tests/test_api.py
```python
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
```