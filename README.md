# SkillBridge V2

A multi-tenant training management platform for colleges with an AI-powered skill gap analysis engine.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite, Tailwind CSS, Shadcn UI |
| Backend | Java 17, Spring Boot 3.5.8, Spring Security, JPA |
| AI Service | Python 3.12, FastAPI, LangChain, MiniLM, pgvector |
| Database | PostgreSQL 17 with pgvector, in Docker (`docker compose up -d`, port 5433) |
| Message Broker | RabbitMQ 4, in Docker (same compose file) |

---

## Documentation

**[docs/README.md](docs/README.md) is the reading order.** The two that matter
most: [docs/DECISIONS.md](docs/DECISIONS.md) — every design decision, the
alternative rejected, and a closing list of what is wrong with this project —
and [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md), which covers how it runs on one
EC2 host and what that deliberately does not do.

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

Start PostgreSQL (port 5433) and RabbitMQ first, from the repository root. The
database password comes from `SKILLBRIDGE_DB_PASSWORD` in the root `.env`:

```bash
docker compose up -d
```

This also starts Mailpit, which catches every email the app sends (invitations
from CSV import): read them at http://localhost:8026.

Copy `skillbridge-backend/src/main/resources/application-local.yaml.example` to
`application-local.yaml` and fill in its placeholders. Flyway builds the schema
on first start.

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

None are kept in this repository, and none ever will be.

`scripts/db/seed-demo.sh` creates the demo accounts and gives them all one
password, generated on first run and written to the gitignored `.env` as
`SKILLBRIDGE_DEMO_PASSWORD`. It is never printed, so it cannot be read off a
screenshot or a terminal recording.

---

## Testing the AI Skill Gap Engine

Once the AI service is running, you can test it directly without needing to fire a RabbitMQ event:

```bash
curl -X POST http://localhost:8000/api/analyze-skills \
  -H "Content-Type: application/json" \
  -H "X-Service-Token: $AI_API_TOKEN" \
  -d '{"student_id": 1, "skills": ["Python", "SQL", "Pandas", "Tableau"]}'
```

Expected response: A JSON object containing the top 5 matching job roles from the pgvector job corpus, with a similarity score and a list of missing skills for each job.

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

> This reads `data/archive/DataAnalyst.csv`, generates 384-dimensional vectors using `all-MiniLM-L6-v2`, and pushed 1,500 job descriptions to the database. It was run once; the source CSV is no longer available, and the embeddings now live only in the database and its backup.

---

## Architecture Overview

```
React Frontend (5173)
        │ REST API
        ▼
Spring Boot Backend (8080)
        │ RabbitMQ
        ▼
Python AI Service (8000)
        │ pgvector cosine similarity search
        ▼
PostgreSQL + pgvector
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
