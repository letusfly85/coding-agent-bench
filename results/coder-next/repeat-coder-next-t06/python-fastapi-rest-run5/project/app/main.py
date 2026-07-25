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

class HealthResponse(BaseModel):
    status: str

class AppState:
    def __init__(self):
        self.tasks: List[Task] = []
        self.next_id: int = 1

def create_app() -> FastAPI:
    app = FastAPI()
    state = AppState()
    
    @app.get("/health", response_model=HealthResponse)
    async def health():
        return {"status": "ok"}
    
    @app.get("/tasks", response_model=List[Task])
    async def get_tasks():
        return state.tasks
    
    @app.post("/tasks", status_code=status.HTTP_201_CREATED, response_model=Task)
    async def create_task(task_create: TaskCreate):
        task = Task(
            id=state.next_id,
            title=task_create.title,
            done=False
        )
        state.tasks.append(task)
        state.next_id += 1
        return task
    
    @app.get("/tasks/{task_id}", response_model=Task)
    async def get_task(task_id: int):
        for task in state.tasks:
            if task.id == task_id:
                return task
        raise HTTPException(status_code=404, detail="Task not found")
    
    @app.put("/tasks/{task_id}", response_model=Task)
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
    
    @app.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
    async def delete_task(task_id: int):
        for i, task in enumerate(state.tasks):
            if task.id == task_id:
                state.tasks.pop(i)
                return
        raise HTTPException(status_code=404, detail="Task not found")
    
    return app

app = create_app()
