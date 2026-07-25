from fastapi import FastAPI, HTTPException
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

class AppState:
    def __init__(self):
        self.tasks: dict[int, Task] = {}
        self.next_id: int = 1

def create_app() -> FastAPI:
    app = FastAPI()
    state = AppState()
    
    @app.get("/health")
    async def health():
        return {"status": "ok"}
    
    @app.get("/tasks")
    async def get_tasks():
        return [state.tasks[tid] for tid in sorted(state.tasks.keys())]
    
    @app.post("/tasks", status_code=201)
    async def create_task(task: TaskCreate):
        task_id = state.next_id
        state.next_id += 1
        new_task = Task(id=task_id, title=task.title, done=False)
        state.tasks[task_id] = new_task
        return new_task
    
    @app.get("/tasks/{task_id}")
    async def get_task(task_id: int):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        return state.tasks[task_id]
    
    @app.put("/tasks/{task_id}")
    async def update_task(task_id: int, task_update: TaskUpdate):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        updated_task = Task(
            id=task_id,
            title=task_update.title,
            done=task_update.done
        )
        state.tasks[task_id] = updated_task
        return updated_task
    
    @app.delete("/tasks/{task_id}", status_code=204)
    async def delete_task(task_id: int):
        if task_id not in state.tasks:
            raise HTTPException(status_code=404, detail="Task not found")
        del state.tasks[task_id]
    
    return app

app = create_app()
