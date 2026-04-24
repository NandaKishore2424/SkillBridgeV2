# Phase 2: COMPLETED ✅
## RAG Pipeline, Vector Search & The AI "Brain" Ingestion

**Completed On:** 2026-04-24  
**Status:** 🟢 Vector Database populated (1,500 jobs). Python AI Service fully modularized and "Intelligence-Enabled".

---

## 1. What We Built (The Big Picture)

If Phase 1 was about the **Communication** (connecting Java to Python), Phase 2 was about the **Knowledge**. 

Before Phase 2, our Python AI service was "hollow"—it could receive messages, but it didn't know anything about the real-world job market. We built a **Retrieval-Augmented Generation (RAG)** pipeline. This means:
1.  **Ingestion:** We took thousands of real-world job descriptions and converted them into mathematical "Vectors."
2.  **Retrieval:** When a student updates their skills, the AI searches this mathematical "Vector Space" to find the most similar jobs in the industry.
3.  **Analysis:** It then compares the student's current skills against the requirements of those top jobs to calculate a **Skill Gap**.

---

## 2. Concepts Learned

### RAG (Retrieval-Augmented Generation)
Standard AI (like ChatGPT) knows a lot but might "hallucinate" or not know your specific data. RAG solves this by letting the AI "look up" facts from a trusted database (our 1,500 jobs) before it gives an answer. 

### Embeddings & Vectors
Computers don't understand "Java Developer" as a string. We use a model called `all-MiniLM-L6-v2` to convert text into an array of **384 decimal numbers (a Vector)**. 
*   **Semantic Search:** Because these vectors represent *meaning*, a search for "Java" will mathematically find "Spring Boot" and "Backend" because they are close to each other in 384-dimensional space.

### pgvector
A PostgreSQL extension that allows us to store these vectors directly in our Supabase database. It adds the `<->` operator, which calculates the **Cosine Distance** (similarity) between two vectors.

### IVFFlat Indexing
Searching through 1,500 vectors one-by-one is slow. An **IVFFlat index** groups similar vectors into "clusters" (lists). When we search, the database only checks the most relevant clusters, making the search nearly instantaneous.

---

## 3. The Dataset Choice: "Data Analyst Jobs" (Kaggle)

We chose the `DataAnalyst.csv` dataset (originally from Glassdoor) for several reasons:
*   **Relevance:** It contains 2,000+ real job listings for Data roles, which perfectly matches our student target audience.
*   **Rich Descriptions:** Each row has a long `Job Description` column which provides enough context for the embedding model to work effectively.
*   **Clean Columns:** It provided the key data we needed: `Job Title`, `Company Name`, `Location`, and `Job Description`.

---

## 4. Database Setup (Supabase SQL)

We executed a major schema update to enable vector intelligence. 

### The Table
We created `industry_job_descriptions` with a specific column for the AI vectors:
```sql
CREATE TABLE industry_job_descriptions (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(500),
    company         VARCHAR(255),
    raw_description TEXT NOT NULL,
    embedding       vector(384) -- 384 matches our MiniLM model dimensions
);
```

### The Search Function
We created a custom PL/pgSQL function so the Python service can easily request a "Similarity Search" in one call:
```sql
CREATE OR REPLACE FUNCTION search_similar_jobs(query_embedding vector(384), match_threshold float, match_count int)
RETURNS TABLE (id bigint, title varchar, company varchar, similarity float)
AS $$
    SELECT id, title, company, 1 - (embedding <-> query_embedding) AS similarity
    FROM industry_job_descriptions
    WHERE 1 - (embedding <-> query_embedding) > match_threshold
    ORDER BY similarity DESC LIMIT match_count;
$$ LANGUAGE sql STABLE;
```

---

## 5. The ETL Ingestion Pipeline (Ubuntu PC)

Because generating embeddings is CPU-intensive, we ran this part exclusively on the **Ryzen 9 Ubuntu PC**. 

### The Ingestion Process (`ingest.py`)
1.  **Loading:** We read the 7.5MB CSV using `pandas`.
2.  **Batching:** We processed the data in **batches of 32**. This is much faster than doing one row at a time because it utilizes the multi-threading of your Ryzen CPU.
3.  **Vectorization:** We used the `sentence-transformers` library to convert each job description into a vector.
4.  **Upsert:** We pushed these vectors into the Supabase cloud.

**Key Engineering Decision:** We only ingested the first 1,500 rows to keep the demonstration snappy and the database within the free-tier limits.

---

## 6. Engineering Principles in the AI Service

We completely rewrote the Python AI service using high-level engineering patterns:

### A. Modular Design (The "Clean Code" Pattern)
Instead of one big `main.py`, we split the code by responsibility:
*   `config.py`: Loads `.env` and fails loudly if a key is missing.
*   `database.py`: Manages the **Threaded Connection Pool**.
*   `embedder.py`: Manages the **Singleton** AI model.
*   `skill_analyzer.py`: Contains the core RAG retrieval logic.

### B. Singleton Pattern
Loading the MiniLM model takes ~300MB of RAM. If we loaded it inside every function, the server would quickly run out of memory. We load it **once** at startup in `embedder.py` and reuse it.

### C. Connection Pooling
Instead of opening a new connection to Supabase for every search, we maintain a "pool" of open connections. This reduces the search time from ~500ms down to ~40ms.

---

## 7. Files Created/Modified in Phase 2

### ETL Pipeline (`skillbridge-etl-pipeline/`)
*   `ingest.py`: The worker script that performed the one-time data load.
*   `.env`: Stores the Supabase connection string.

### AI Service (`skillbridge-ai-service/`)
*   `config.py`: Environment variable loader.
*   `database.py`: Implementation of the `ThreadedConnectionPool`.
*   `embedder.py`: Model wrapper using `langchain-huggingface`.
*   `skill_analyzer.py`: The RAG brain. It vectorizes input -> queries Supabase -> returns matches.
*   `main.py`: Updated to include a new REST API endpoint `/api/analyze-skills` for manual testing.
*   `requirements.txt`: Updated with `langchain-huggingface`, `sentence-transformers`, and `psycopg2-binary`.

---

## 8. How to Test Phase 2

You can now test the entire "Intelligence" of the system with a single `curl` command. This simulates a student having specific skills and asks the AI to find matches:

```bash
curl -X POST http://localhost:8000/api/analyze-skills \
  -H "Content-Type: application/json" \
  -d '{
    "student_id": 1, 
    "skills": ["Python", "SQL", "Pandas", "Tableau"]
  }'
```

---

## Phase 2 Checklist

- [x] **Database:** `pgvector` enabled and `industry_job_descriptions` table created.
- [x] **ETL:** 1,500 job descriptions successfully embedded and uploaded from Ubuntu PC.
- [x] **Architecture:** AI Service modularized with Config, DB Pool, and Singleton Embedder.
- [x] **Embeddings:** Switched to `langchain-huggingface` for long-term support.
- [x] **RAG Retrieval:** Vector similarity search implemented and verified.
- [x] **Skill Gap:** Logic for extracting missing skills from job matches implemented.
- [x] **Documentation:** Comprehensive `README.md` and `Phase_2_COMPLETED.md` created.

---

## Next Step: Phase 3 — LangGraph Autonomous Agents
Now that we have the data and the ability to find skill gaps, we will build the **Autonomous Mock Interviewer**. We will use **LangGraph** to create a conversation state machine that uses the **Groq API** to interview students specifically on the "Missing Skills" we found in Phase 2.
