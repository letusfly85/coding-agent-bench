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
