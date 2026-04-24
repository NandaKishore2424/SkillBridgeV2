# SkillBridge V2

A multi-tenant training management platform for colleges with an AI-powered skill gap analysis engine.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite, Tailwind CSS, Shadcn UI |
| Backend | Java 17, Spring Boot 3.5.8, Spring Security, JPA, Flyway |
| AI Service | Python 3.12, FastAPI, LangChain, MiniLM, pgvector |
| Database | PostgreSQL on Supabase |
| Message Broker | RabbitMQ via CloudAMQP |

---

## Running the Full Stack

Open **3 separate terminals** and run each service.

### Terminal 1 — React Frontend

```bash
cd skillbridge-frontend
npm install        # first time only
npm run dev
```

> Runs on: **http://localhost:5173**

---

### Terminal 2 — Spring Boot Backend

Make sure `skillbridge-backend/src/main/resources/application-local.yaml` exists with your Supabase credentials:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://YOUR_SUPABASE_HOST:5432/postgres
    username: postgres.YOUR_PROJECT_REF
    password: YOUR_PASSWORD
```

Then run:

```bash
cd skillbridge-backend
mvn spring-boot:run
```

> Runs on: **http://localhost:8080**

---

### Terminal 3 — Python AI Service

The AI service uses a virtual environment to avoid conflicts with system Python.

**First time setup:**
```bash
cd skillbridge-ai-service
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

**Every subsequent run:**
```bash
cd skillbridge-ai-service
source venv/bin/activate
python -m uvicorn main:app --reload --port 8000
```

> Runs on: **http://localhost:8000**

---

## Test Credentials

| Role | Email | Password |
|---|---|---|
| System Admin | `r.nandakishore24@gmail.com` | `Password123!@#` |
| College Admin | `admin@skillbridge.com` | `Password123!@#` |

---

## Testing the AI Skill Gap Engine

Once the AI service is running, you can test it directly without needing to fire a RabbitMQ event:

```bash
curl -X POST http://localhost:8000/api/analyze-skills \
  -H "Content-Type: application/json" \
  -d '{"student_id": 1, "skills": ["Python", "SQL", "Pandas", "Tableau"]}'
```

Expected response: A JSON object containing the top 5 matching job roles from our Supabase vector database, with a similarity score and a list of missing skills for each job.

---

## ETL Pipeline (One-Time Setup — Ubuntu PC Only)

The vector database was built using a one-time ETL pipeline on the Ubuntu PC:

```bash
cd skillbridge-etl-pipeline
python3 -m venv venv
source venv/bin/activate
pip install langchain langchain-community sentence-transformers pandas psycopg2-binary python-dotenv tqdm
python ingest.py
```

> This reads `data/archive/DataAnalyst.csv`, generates 384-dimensional vectors using `all-MiniLM-L6-v2`, and pushes 1,500 job descriptions to Supabase. Run this once only.

---

## Architecture Overview

```
React Frontend (5173)
        │ REST API
        ▼
Spring Boot Backend (8080)
        │ RabbitMQ (CloudAMQP)
        ▼
Python AI Service (8000)
        │ pgvector cosine similarity search
        ▼
Supabase PostgreSQL
  ├── App Tables (colleges, students, batches...)
  └── industry_job_descriptions (1500 rows + embeddings)
```

---

## Version 2 AI Roadmap

| Phase | Status | Description |
|---|---|---|
| Phase 1 — Event Bus | ✅ Done | RabbitMQ decoupling between Java and Python |
| Phase 2 — RAG Pipeline | ✅ Done | ETL ingestion + vector similarity search |
| Phase 3 — LangGraph Agents | 🔜 Next | Autonomous Mock Interview with Groq LLM |
| Phase 4 — WebSockets | 🔜 Later | Real-time push notifications to React |
