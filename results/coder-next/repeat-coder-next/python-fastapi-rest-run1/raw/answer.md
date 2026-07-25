### FILE: app/__init__.py
```python
from .main import app, create_app

__all__ = ['app', 'create_app']
```

### FILE: app/main.py
```python
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel

app = FastAPI()

class Task(BaseModel):
    id: int
    title: str
    done: bool

class TaskCreate(BaseModel):
    title: str

class TaskUpdate(BaseModel):
    title: str
    done: bool

# In-memory storage
tasks: dict[int, Task] = {}
next_id = 1

@app.get("/health")
def health():
    return {"status": "ok"}

@app.get("/tasks")
def get_tasks():
    return [tasks[tid] for tid in sorted(tasks.keys())]

@app.post("/tasks", status_code=201)
def create_task(task_create: TaskCreate):
    global next_id
    task = Task(id=next_id, title=task_create.title, done=False)
    tasks[next_id] = task
    next_id += 1
    return task

@app.get("/tasks/{task_id}")
def get_task(task_id: int):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="Task not found")
    return tasks[task_id]

@app.put("/tasks/{task_id}")
def update_task(task_id: int, task_update: TaskUpdate):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="Task not found")
    task = Task(id=task_id, title=task_update.title, done=task_update.done)
    tasks[task_id] = task
    return task

@app.delete("/tasks/{task_id}", status_code=204)
def delete_task(task_id: int):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="Task not found")
    del tasks[task_id]
    return Response(status_code=204)

def create_app() -> FastAPI:
    global tasks, next_id
    # Reset state for a fresh app
    tasks = {}
    next_id = 1
    return app
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
```