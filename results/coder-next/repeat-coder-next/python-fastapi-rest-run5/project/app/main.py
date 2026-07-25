from typing import List, Optional
from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel

class Task(BaseModel):
    id: int
    title: str
    done: bool = False

class TaskCreate(BaseModel):
    title: str

class TaskUpdate(BaseModel):
    title: str
    done: bool

class HealthCheck(BaseModel):
    status: str

# In-memory store
tasks: List[Task] = []
next_id: int = 1

def create_app() -> FastAPI:
    global tasks, next_id
    tasks = []
    next_id = 1
    
    app = FastAPI()
    
    @app.get("/health", response_model=HealthCheck)
    def health():
        return {"status": "ok"}
    
    @app.get("/tasks", response_model=List[Task])
    def get_tasks():
        return sorted(tasks, key=lambda t: t.id)
    
    @app.post("/tasks", response_model=Task, status_code=201)
    def create_task(task_create: TaskCreate):
        global next_id
        task = Task(id=next_id, title=task_create.title, done=False)
        next_id += 1
        tasks.append(task)
        return task
    
    @app.get("/tasks/{task_id}", response_model=Task)
    def get_task(task_id: int):
        for task in tasks:
            if task.id == task_id:
                return task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @app.put("/tasks/{task_id}", response_model=Task)
    def update_task(task_id: int, task_update: TaskUpdate):
        for task in tasks:
            if task.id == task_id:
                task.title = task_update.title
                task.done = task_update.done
                return task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @app.delete("/tasks/{task_id}", status_code=204)
    def delete_task(task_id: int, response: Response):
        for i, task in enumerate(tasks):
            if task.id == task_id:
                tasks.pop(i)
                return Response(status_code=204)
        raise HTTPException(status_code=404, detail="Task not found")
    
    return app

app = create_app()
