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
