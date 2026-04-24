# Phase 2: Real Data Ingestion & RAG Vector Construction
## The Complete Guide — Everything You Need to Know

**Environment:** Ubuntu PC (32GB RAM) — Heavy computation phase  
**Laptop Role:** Only browser work (Supabase setup + verification)  
**Goal:** Build the "brain" of the AI — a searchable database of 1000+ real job descriptions converted into mathematical vectors

---

## Table of Contents
1. What Are We Building? (The Big Picture)
2. Core Concepts Explained Simply
3. The Tech Stack for Phase 2
4. Pre-Requisites & What to Install
5. Step 1 — Supabase Database Setup (Laptop)
6. Step 2 — Ubuntu PC Environment Setup
7. Step 3 — Getting Real Data (Kaggle)
8. Step 4 — Understanding the ETL Pipeline
9. Step 5 — Writing the Ingestion Script
10. Step 6 — Running the Pipeline
11. Step 7 — Verification
12. Step 8 — Connecting Python AI Service to Supabase Vectors
13. Step 9 — Writing the Skill Gap Query
14. Common Errors & How to Fix Them
15. Phase 2 Complete Checklist

---

## 1. What Are We Building? (The Big Picture)

Right now, our Python AI service does this when a message arrives from Java:
```
[x] Received: {'eventType': 'SKILL_UPDATED', 'studentId': 5}
     → Print to console. Done. Nothing else.
```

That is useless. We need the Python AI service to actually DO something intelligent. Specifically:

> **"Given a student's current skills (e.g., Java, Spring Boot, React), find the TOP 5 real-world job roles that match them best, and identify the skill GAPS — what skills they are missing to qualify for those roles."**

To do this, the AI needs a **library of real job descriptions** to compare against. Not mock data we made up — real postings from companies like Google, Infosys, TCS, Accenture, etc.

This is the **RAG pipeline — Retrieval Augmented Generation:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    Phase 2 Architecture                         │
│                                                                 │
│  [Kaggle Dataset]           [Ubuntu PC]          [Supabase DB] │
│  1000+ Job CSVs    ──ETL──► Python Script  ──►  Job Vectors    │
│                              Embeds text                        │
│                                                                 │
│  Later (Phase 3):                                               │
│  Student Profile ──────────► Python AI ◄──── Supabase Search   │
│                               LangGraph        (pgvector)       │
│                                  │                              │
│                                  ▼                              │
│                           Gap Analysis Report                   │
└─────────────────────────────────────────────────────────────────┘
```

The term **ETL** is a corporate engineering term:
- **E**xtract — Pull raw data from the source (Kaggle CSV)
- **T**ransform — Process it (clean text, generate embedding vectors)
- **L**oad — Store it in the target destination (Supabase pgvector table)

---

## 2. Core Concepts Explained Simply

### 2.1 What is a Vector Embedding?

A vector is just a **list of numbers** that represents the meaning of text.

Example:
```
"Java Developer with Spring Boot experience"
→ [-0.023, 0.412, 0.891, -0.156, 0.044, ...] (384 numbers)

"Python Engineer with FastAPI knowledge"  
→ [-0.019, 0.388, 0.805, -0.201, 0.091, ...] (384 numbers)
```

The **magic**: texts that are *semantically similar* (mean similar things) produce vectors that are *mathematically close* to each other.

Think of it like GPS coordinates:
- Mumbai and Pune are close coordinates → they are near each other
- Mumbai and New York are far coordinates → they are far apart

Similarly:
- "Java Backend Engineer" and "Spring Boot Developer" → close vectors (similar meaning)
- "Java Backend Engineer" and "Chef" → far vectors (completely different meaning)

When a student says "I know Java and Spring Boot", we convert that to a vector, then search our database for job descriptions whose vectors are closest to it. That's how we find the best matching jobs!

### 2.2 What is the MiniLM Model?

`all-MiniLM-L6-v2` is a small, open-source AI model from Microsoft/HuggingFace that specializes in converting text into these 384-dimension vectors. It runs **completely free** and **locally** on your Ubuntu PC.

Why MiniLM and not GPT or Claude?
- It's free (no API costs)
- It runs locally (no internet required once downloaded)
- It's purpose-built for creating embeddings (not chat)
- 384 dimensions are enough for semantic search — bigger is not always better
- It processes ~100 sentences per second on CPU (your 32GB Ubuntu can handle thousands)

### 2.3 What is pgvector?

`pgvector` is an **extension** for PostgreSQL (the database inside Supabase). It adds a new column type called `vector(n)` which can store embedding vectors. More importantly, it adds a special search operator that finds the most similar vectors to a given query vector.

Instead of searching by text like:
```sql
SELECT * FROM jobs WHERE description LIKE '%Java%'
```

We search by meaning like:
```sql
SELECT * FROM jobs ORDER BY embedding <-> query_vector LIMIT 5;
```

The `<->` operator calculates the **cosine distance** (how similar two vectors are). The jobs with the smallest distance are the most semantically relevant.

### 2.4 What is Cosine Distance?

Imagine two arrows drawn from the same starting point. If they point in exactly the same direction, the angle between them is 0° → cosine distance = 0 = **perfectly similar**.

If they point in completely opposite directions, the angle is 180° → cosine distance = 2 = **completely different**.

This is how the AI measures similarity between a student's skill vector and a job description vector.

### 2.5 What is LangChain?

LangChain is a popular Python library that provides easy-to-use wrappers around common AI operations. Instead of writing complex code to load HuggingFace models and generate embeddings, LangChain gives us:

```python
# Without LangChain (complex):
from transformers import AutoTokenizer, AutoModel
import torch
tokenizer = AutoTokenizer.from_pretrained("sentence-transformers/all-MiniLM-L6-v2")
model = AutoModel.from_pretrained("sentence-transformers/all-MiniLM-L6-v2")
inputs = tokenizer("Java Developer", return_tensors="pt", padding=True, truncation=True)
with torch.no_grad():
    outputs = model(**inputs)
embeddings = outputs.last_hidden_state.mean(dim=1).numpy()

# With LangChain (simple):
from langchain.embeddings import HuggingFaceEmbeddings
embedder = HuggingFaceEmbeddings(model_name="all-MiniLM-L6-v2")
vector = embedder.embed_query("Java Developer")
```

We will use LangChain exclusively for embedding in Phase 2.

---

## 3. The Tech Stack for Phase 2

| Technology | What It Is | Why We Use It |
|---|---|---|
| **Python 3.x** | Programming language | Our ETL script is written in Python |
| **LangChain** | AI library wrapper | Simplifies HuggingFace model loading and embedding |
| **sentence-transformers** | Open-source model library | Contains the MiniLM model we use |
| **HuggingFace `all-MiniLM-L6-v2`** | Embedding model | Converts text → 384-dim vectors, free, local |
| **pandas** | Data manipulation library | Reads and cleans the Kaggle CSV file |
| **psycopg2-binary** | PostgreSQL driver for Python | Connects Python to Supabase database |
| **supabase-py** | Supabase Python SDK | Alternative way to interact with Supabase |
| **pgvector** | PostgreSQL extension | Enables vector storage + similarity search in Supabase |
| **Supabase** | Cloud PostgreSQL | Stores job descriptions + their vectors globally accessible |
| **Kaggle** | Dataset platform | Source of 1000+ real-world job descriptions |

---

## 4. Pre-Requisites & What to Install

### On the Laptop (browser only):
- Supabase account login
- Kaggle account (free at kaggle.com)

### On the Ubuntu 32GB PC:
- Python 3.10+ (Ubuntu typically has this pre-installed)
- `pip` package manager
- Internet connection (to download HuggingFace model one time — ~90MB)

---

## 5. Step 1 — Supabase Database Setup (Laptop)

> **This step runs on your LAPTOP in the browser. Do NOT do this on Ubuntu.**

### Step 1a: Enable pgvector Extension

1. Log in to [supabase.com](https://supabase.com)
2. Click on your SkillBridge project
3. Click **SQL Editor** in the left sidebar
4. Paste this SQL and click **Run**:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

You should see: `Success. No rows returned.`

This enables the `vector` data type and the `<->` (cosine distance) operator in your database.

### Step 1b: Create the Job Descriptions Table

Paste this SQL in the SQL Editor and click Run:

```sql
CREATE TABLE IF NOT EXISTS industry_job_descriptions (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(500),
    company         VARCHAR(255),
    location        VARCHAR(255),
    required_skills TEXT,
    raw_description TEXT NOT NULL,
    embedding       vector(384),
    source          VARCHAR(100) DEFAULT 'kaggle',
    created_at      TIMESTAMP DEFAULT NOW()
);
```

**Explanation of each column:**
- `id` → Auto-incrementing unique identifier for each job
- `title` → Job title e.g., "Senior Java Backend Engineer"
- `company` → Company name e.g., "Infosys"
- `location` → Job location e.g., "Bengaluru, India"
- `required_skills` → A comma-separated list of skills from the job posting
- `raw_description` → The full text of the job description (this is what we embed)
- `embedding` → The 384-dimensional vector generated from the raw_description
- `source` → Where we got this data (for tracking)
- `created_at` → Timestamp for auditing

### Step 1c: Create the Vector Similarity Search Index

For fast vector search on thousands of rows, we need an index:

```sql
CREATE INDEX IF NOT EXISTS idx_job_embedding 
ON industry_job_descriptions 
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
```

**What is IVFFlat?**  
IVFFlat is an approximate nearest-neighbor index. Instead of comparing every row to your query (slow on 10,000 rows), it organizes vectors into 100 clusters (lists) and only searches the relevant clusters. Think of it like a library organized by genre — you don't check every book, just the right section.

### Step 1d: Create a Database Function for Similarity Search

This SQL function will be called by Python later to find matching jobs:

```sql
CREATE OR REPLACE FUNCTION search_similar_jobs(
    query_embedding vector(384),
    match_threshold float DEFAULT 0.5,
    match_count int DEFAULT 10
)
RETURNS TABLE (
    id bigint,
    title varchar,
    company varchar,
    required_skills text,
    similarity float
)
LANGUAGE sql STABLE
AS $$
    SELECT
        id,
        title,
        company,
        required_skills,
        1 - (embedding <-> query_embedding) AS similarity
    FROM industry_job_descriptions
    WHERE 1 - (embedding <-> query_embedding) > match_threshold
    ORDER BY embedding <-> query_embedding
    LIMIT match_count;
$$;
```

**What is `1 - (embedding <-> query_embedding)`?**
The `<->` operator returns distance (0 = same, 2 = opposite). Subtracting from 1 converts it to similarity (1 = same, -1 = opposite). We only return results above a threshold of 0.5, meaning at least 50% similar.

---

## 6. Step 2 — Ubuntu PC Environment Setup

> **Everything from this step onwards runs on your UBUNTU PC**

### Step 2a: Access Your Ubuntu PC
SSH into it from your laptop if you are remote, or sit directly in front of it.

```bash
# If SSH-ing from your laptop:
ssh your_username@YOUR_UBUNTU_PC_IP
```

### Step 2b: Verify Python Version
```bash
python3 --version
# Should show Python 3.10, 3.11, or 3.12
```

### Step 2c: Create a Project Directory
```bash
mkdir -p ~/skillbridge-etl-pipeline
cd ~/skillbridge-etl-pipeline
```

### Step 2d: Set Up a Python Virtual Environment

A virtual environment is like a sandbox — it isolates the Python packages for this project from the rest of your system. This prevents version conflicts.

```bash
python3 -m venv venv
source venv/bin/activate
# Your prompt should now show (venv) at the start
```

### Step 2e: Install Dependencies

This is where the Ubuntu PC's 32GB RAM shines. These packages are large:

```bash
pip install \
  langchain \
  langchain-community \
  sentence-transformers \
  pandas \
  psycopg2-binary \
  python-dotenv \
  tqdm
```

**What each package does:**
- `langchain` + `langchain-community` → Framework for AI operations
- `sentence-transformers` → Downloads and runs the MiniLM embedding model locally
- `pandas` → Reads and cleans CSV files (like Excel for Python)
- `psycopg2-binary` → The PostgreSQL driver (connects Python to Supabase DB)
- `python-dotenv` → Loads secrets from a `.env` file so you don't hardcode passwords
- `tqdm` → Shows a progress bar so you can see how many rows have been processed

---

## 7. Step 3 — Getting Real Data (Kaggle)

### Step 3a: Create a Kaggle Account (Laptop Browser)
1. Go to [kaggle.com](https://kaggle.com) and sign up (free)
2. Once logged in, go to your profile → **Settings** → **API**
3. Click **"Create New Token"** — this downloads a file called `kaggle.json`
4. Transfer this file to your Ubuntu PC (via USB, SCP, or email)

### Step 3b: Set Up Kaggle CLI on Ubuntu
```bash
pip install kaggle
mkdir -p ~/.kaggle
cp kaggle.json ~/.kaggle/kaggle.json
chmod 600 ~/.kaggle/kaggle.json
```

### Step 3c: Download the Job Descriptions Dataset

We will use this real dataset: **"Data Science Job Listing with Skills"** by aseem149:

```bash
kaggle datasets download -d aseem149/data-scientist-job-listing-with-skills
unzip data-scientist-job-listing-with-skills.zip -d data/
ls data/
```

**Alternative datasets you can also use:**
- `arjunbhasin2013/csgo-dataset` — No, wrong one. Try these:
- `andrewmvd/data-scientist-jobs`
- `lukebarousse/data-science-job-postings-and-skills`

If Kaggle CLI doesn't work, you can manually:
1. Go to kaggle.com in your laptop browser
2. Search: "job descriptions skills"
3. Download any CSV dataset with job title, description, required skills columns
4. Transfer the CSV to Ubuntu via USB or `scp`

### Step 3d: Explore the Data

Open Python interactively and look at your data:

```bash
python3
```

```python
import pandas as pd
df = pd.read_csv('data/job_descriptions.csv')  # adjust filename
print(df.head(3))
print(df.columns.tolist())
print(f"Total rows: {len(df)}")
```

Note down:
- The **column name** for the job title
- The **column name** for the job description text
- The **column name** for required skills (if present)

---

## 8. Step 4 — Understanding the ETL Pipeline

Before writing code, let's understand what the script will do step by step:

```
CSV File (1000+ rows)
        │
        ▼
  pandas.read_csv()
  (loads data into memory)
        │
        ▼
  Clean the data:
  - Remove rows where description is empty
  - Remove duplicates
  - Trim long descriptions to 512 tokens
        │
        ▼
  For each row (with progress bar):
  │
  ├── Combine title + description into one text string
  │
  ├── embedder.embed_documents([text])
  │   → MiniLM model runs on CPU
  │   → Returns a list of 384 floats
  │
  └── psycopg2 INSERT into Supabase
      → Sends row + vector to cloud database
        │
        ▼
  Done! 1000+ rows with vectors in Supabase
```

**Performance estimate on Ubuntu 32GB RAM:**
- MiniLM model loading: ~30 seconds (one time)
- Embedding speed: ~50-100 docs/second on CPU
- 1000 job descriptions: ~10-20 minutes total
- Memory usage: ~4-6GB RAM (your 32GB is more than enough)

---

## 9. Step 5 — Writing the Ingestion Script

### Step 5a: Create the .env file

Never hardcode passwords in code. Create a `.env` file:

```bash
nano .env
```

Paste this content (use your actual Supabase credentials):

```env
# Supabase Direct Connection (use Session Pooler if direct doesn't work on your network)
SUPABASE_DB_URL=postgresql://postgres.ooqxedojvxetpwjldxab:24Skillbridge%40@aws-1-ap-northeast-2.pooler.supabase.com:5432/postgres

# Supabase Project URL and Anon Key (found in Supabase dashboard → Settings → API)
SUPABASE_URL=https://ooqxedojvxetpwjldxab.supabase.co
SUPABASE_KEY=your_supabase_anon_key_here
```

> **Important:** The `@` in the password must be URL-encoded as `%40` inside a connection string. So `24Skillbridge@` becomes `24Skillbridge%40`.

Save and exit: `CTRL+X`, then `Y`, then `ENTER`.

### Step 5b: Write the Main Ingestion Script

Create the file:
```bash
nano ingest.py
```

Paste the full script below:

```python
"""
SkillBridge Phase 2 — RAG Data Ingestion Pipeline
====================================================
This script reads real job description data from a CSV file,
generates 384-dimensional vector embeddings for each description,
and stores them in the Supabase pgvector table.

Run on: Ubuntu PC (32GB RAM)
Estimated runtime: 10-20 minutes for 1000 rows
"""

import os
import pandas as pd
import psycopg2
from tqdm import tqdm
from dotenv import load_dotenv
from langchain_community.embeddings import HuggingFaceEmbeddings

# ─── 1. Load Environment Variables ─────────────────────────────────────────────
load_dotenv()
DB_URL = os.getenv("SUPABASE_DB_URL")

if not DB_URL:
    raise ValueError("SUPABASE_DB_URL not found in .env file! Check your .env setup.")

# ─── 2. Load the HuggingFace Embedding Model ───────────────────────────────────
print("Loading MiniLM embedding model (downloading ~90MB the first time)...")
print("This downloads once and is cached forever after.")

embedder = HuggingFaceEmbeddings(
    model_name="sentence-transformers/all-MiniLM-L6-v2",
    model_kwargs={"device": "cpu"},   # use "cuda" if Ubuntu has a GPU
    encode_kwargs={"normalize_embeddings": True}  # needed for cosine similarity
)
print("✓ Model loaded successfully!\n")

# ─── 3. Load and Clean the CSV Data ────────────────────────────────────────────
print("Loading dataset...")

# !! CHANGE THIS to your actual CSV filename !!
CSV_FILE = "data/job_descriptions.csv"
df = pd.read_csv(CSV_FILE)

print(f"✓ Loaded {len(df)} rows. Columns: {df.columns.tolist()}\n")

# !! CHANGE THESE column names to match your actual CSV columns !!
TITLE_COL = "Job Title"           # The job title column in your CSV
DESCRIPTION_COL = "Job Description"  # The job description column in your CSV
SKILLS_COL = "skills"             # Skills column (optional — use None if missing)
COMPANY_COL = "Company"           # Company name column (optional — use None if missing)
LOCATION_COL = "Location"         # Location column (optional — use None if missing)

# Clean step 1: Remove rows with no description
df = df.dropna(subset=[DESCRIPTION_COL])
print(f"After dropping empty descriptions: {len(df)} rows")

# Clean step 2: Remove duplicate descriptions
df = df.drop_duplicates(subset=[DESCRIPTION_COL])
print(f"After removing duplicates: {len(df)} rows")

# Clean step 3: Truncate very long descriptions (MiniLM handles max 256 tokens)
df[DESCRIPTION_COL] = df[DESCRIPTION_COL].str[:2000]

# Limit to 1500 rows to keep the run manageable
df = df.head(1500)
print(f"\nWill process: {len(df)} job descriptions\n")

# ─── 4. Connect to Supabase Database ───────────────────────────────────────────
print("Connecting to Supabase database...")
conn = psycopg2.connect(DB_URL)
cursor = conn.cursor()
print("✓ Connected to Supabase!\n")

# ─── 5. Main ETL Loop ──────────────────────────────────────────────────────────
print("Starting ETL pipeline...")
print("Each row: combine text → embed → insert into Supabase")
print("-" * 60)

success_count = 0
error_count = 0

# Batch processing — embed 32 texts at a time instead of 1 (much faster)
BATCH_SIZE = 32

for batch_start in tqdm(range(0, len(df), BATCH_SIZE), desc="Processing batches"):
    batch = df.iloc[batch_start : batch_start + BATCH_SIZE]
    
    # Build a combined text for each row in the batch
    # Combining title + description gives the model more context
    texts = []
    for _, row in batch.iterrows():
        title = str(row.get(TITLE_COL, "")) if TITLE_COL else ""
        description = str(row[DESCRIPTION_COL])
        combined = f"Job Title: {title}\n\nDescription:\n{description}"
        texts.append(combined)
    
    try:
        # Generate embeddings for the entire batch at once (much faster than one by one)
        vectors = embedder.embed_documents(texts)
        
        # Insert each row + its vector into Supabase
        for i, (_, row) in enumerate(batch.iterrows()):
            title = str(row.get(TITLE_COL, "Unknown")) if TITLE_COL else "Unknown"
            company = str(row.get(COMPANY_COL, "")) if COMPANY_COL else ""
            location = str(row.get(LOCATION_COL, "")) if LOCATION_COL else ""
            skills = str(row.get(SKILLS_COL, "")) if SKILLS_COL else ""
            description = str(row[DESCRIPTION_COL])
            vector = vectors[i]
            
            # Convert Python list to PostgreSQL vector format
            vector_str = "[" + ",".join(str(v) for v in vector) + "]"
            
            cursor.execute("""
                INSERT INTO industry_job_descriptions 
                    (title, company, location, required_skills, raw_description, embedding)
                VALUES (%s, %s, %s, %s, %s, %s::vector)
            """, (title, company, location, skills, description, vector_str))
            
            success_count += 1
        
        # Commit every batch (save to DB progressively, don't wait till end)
        conn.commit()
        
    except Exception as e:
        error_count += 1
        conn.rollback()  # Roll back only the failed batch
        print(f"\n⚠ Error in batch starting at row {batch_start}: {e}")
        continue

# ─── 6. Cleanup ─────────────────────────────────────────────────────────────────
cursor.close()
conn.close()

# ─── 7. Final Summary ──────────────────────────────────────────────────────────
print("\n" + "=" * 60)
print("✅  ETL PIPELINE COMPLETE!")
print(f"   Successfully inserted: {success_count} job descriptions")
print(f"   Failed rows: {error_count}")
print(f"   Database: Supabase pgvector table 'industry_job_descriptions'")
print("=" * 60)
print("\nNext step: Open Supabase dashboard on your LAPTOP")
print("and verify the table has rows with populated 'embedding' columns.")
```

---

## 10. Step 6 — Running the Pipeline

### Step 6a: Verify the script can see the model

```bash
# Make sure venv is active
source venv/bin/activate

# Test the model loads correctly (run this first before the full pipeline)
python3 -c "
from langchain_community.embeddings import HuggingFaceEmbeddings
e = HuggingFaceEmbeddings(model_name='sentence-transformers/all-MiniLM-L6-v2')
vec = e.embed_query('Test Java developer job')
print(f'Success! Vector has {len(vec)} dimensions. First 3 values: {vec[:3]}')
"
```

If this prints `Success! Vector has 384 dimensions.` — you are ready to run the full pipeline.

### Step 6b: Run the Full ETL Pipeline

```bash
python3 ingest.py
```

You will see output like:
```
Loading MiniLM embedding model (downloading ~90MB the first time)...
This downloads once and is cached forever after.
✓ Model loaded successfully!

Loading dataset...
✓ Loaded 3241 rows. Columns: ['Job Title', 'Company', 'Job Description', 'skills']

After dropping empty descriptions: 3198 rows
After removing duplicates: 2890 rows
Will process: 1500 job descriptions

Connecting to Supabase database...
✓ Connected to Supabase!

Starting ETL pipeline...
Processing batches: 100%|████████████| 47/47 [12:34<00:00, 16.04s/it]

============================================================
✅  ETL PIPELINE COMPLETE!
   Successfully inserted: 1500 job descriptions
   Failed rows: 0
   Database: Supabase pgvector table 'industry_job_descriptions'
============================================================
```

> **If you need to re-run:** Truncate the table first to avoid duplicates:
> ```sql
> TRUNCATE TABLE industry_job_descriptions RESTART IDENTITY;
> ```

---

## 11. Step 7 — Verification (Laptop Browser)

### Step 7a: Check Row Count in Supabase

Go to your Supabase dashboard → SQL Editor → Run:

```sql
SELECT COUNT(*) FROM industry_job_descriptions;
-- Should show: 1500 (or however many you ingested)
```

### Step 7b: Check That Vectors Are Populated

```sql
SELECT 
    id, 
    title, 
    company,
    LEFT(raw_description, 100) AS description_preview,
    (embedding IS NOT NULL) AS has_embedding,
    vector_dims(embedding) AS vector_dimensions
FROM industry_job_descriptions
LIMIT 5;
```

Expected output:
| id | title | company | description_preview | has_embedding | vector_dimensions |
|----|-------|---------|---------------------|---------------|-------------------|
| 1 | Java Developer | TCS | Java developer with 3+ years... | true | 384 |
| 2 | Data Scientist | Infosys | We are looking for a... | true | 384 |

### Step 7c: Test Similarity Search

This is the most exciting test. Run this to see if the search works:

```sql
-- This simulates what Python will do when a student profile arrives
-- We're searching for jobs similar to "Java Spring Boot developer"

SELECT 
    id, 
    title, 
    company,
    1 - (embedding <-> (
        -- This is a placeholder — in real use, Python sends the actual vector
        -- For testing, we compare a job against other jobs
        SELECT embedding FROM industry_job_descriptions WHERE id = 1 LIMIT 1
    )) AS similarity
FROM industry_job_descriptions
ORDER BY embedding <-> (
    SELECT embedding FROM industry_job_descriptions WHERE id = 1 LIMIT 1
)
LIMIT 10;
```

You should see the 10 most similar job descriptions to row ID 1, ranked by similarity score (closer to 1.0 = more similar).

---

## 12. Step 8 — Connecting Python AI Service to Supabase Vectors

Now we go back to the **Laptop** and update the Python AI service (`skillbridge-ai-service/main.py`) to use the vectors we stored.

### Step 8a: Update requirements.txt

On the Laptop, inside `skillbridge-ai-service/`:

```bash
# Add these new dependencies
pip install langchain langchain-community sentence-transformers psycopg2-binary
```

Also update `requirements.txt`:
```
fastapi
uvicorn
pika
python-dotenv
langchain
langchain-community
sentence-transformers
psycopg2-binary
```

### Step 8b: Add Query Logic to main.py

We will add a new function `analyze_student_skills()` that:
1. Takes the student's skill list
2. Converts it to a vector using the same MiniLM model
3. Queries Supabase for the top 5 matching jobs
4. Returns the result

```python
# Add this import at the top of main.py
import psycopg2
from langchain_community.embeddings import HuggingFaceEmbeddings

# Add this global variable (loads the model ONCE when the server starts)
EMBEDDING_MODEL = None
DB_URL = os.getenv("SUPABASE_DB_URL")

@app.on_event("startup")
async def load_model():
    """Load the embedding model once when the FastAPI server starts."""
    global EMBEDDING_MODEL
    print("Loading embedding model...")
    EMBEDDING_MODEL = HuggingFaceEmbeddings(
        model_name="sentence-transformers/all-MiniLM-L6-v2",
        model_kwargs={"device": "cpu"},
        encode_kwargs={"normalize_embeddings": True}
    )
    print("✓ Embedding model ready!")

def analyze_student_skills(student_skills: list[str]) -> dict:
    """
    Given a list of student skills, find the top 5 most relevant job roles
    and identify what additional skills they are missing.
    
    Example input: ["Java", "Spring Boot", "MySQL", "REST APIs"]
    """
    # Step 1: Convert the skill list into a search query string
    query_text = f"Skills: {', '.join(student_skills)}\nLooking for relevant software engineering job roles."
    
    # Step 2: Embed the query using MiniLM
    query_vector = EMBEDDING_MODEL.embed_query(query_text)
    
    # Step 3: Query Supabase for similar jobs
    vector_str = "[" + ",".join(str(v) for v in query_vector) + "]"
    
    conn = psycopg2.connect(DB_URL)
    cursor = conn.cursor()
    
    cursor.execute("""
        SELECT title, company, required_skills,
               1 - (embedding <-> %s::vector) AS similarity
        FROM industry_job_descriptions
        ORDER BY embedding <-> %s::vector
        LIMIT 5;
    """, (vector_str, vector_str))
    
    results = cursor.fetchall()
    cursor.close()
    conn.close()
    
    # Step 4: Format and return the results
    matched_jobs = []
    for title, company, required_skills, similarity in results:
        matched_jobs.append({
            "job_title": title,
            "company": company,
            "required_skills": required_skills,
            "match_score": round(similarity * 100, 1)  # e.g., 78.4%
        })
    
    return {
        "student_skills": student_skills,
        "matched_jobs": matched_jobs
    }

# Add this new API endpoint
@app.post("/analyze-skills")
async def analyze_skills(payload: dict):
    """
    REST endpoint that Java can call to get skill gap analysis.
    
    Payload: {"student_id": 5, "skills": ["Java", "Spring Boot", "MySQL"]}
    """
    skills = payload.get("skills", [])
    student_id = payload.get("student_id")
    
    if not skills:
        return {"error": "No skills provided"}
    
    result = analyze_student_skills(skills)
    result["student_id"] = student_id
    
    return result
```

---

## 13. Step 9 — Writing the Skill Gap Query

Now that we have matching jobs, we need to identify the **gap** — what skills the student doesn't have that the matched jobs require.

```python
def compute_skill_gap(student_skills: list[str], job_required_skills: str) -> list[str]:
    """
    Compare student skills against job required skills and return missing ones.
    
    Example:
        student_skills = ["Java", "Spring Boot"]
        job_required_skills = "Java, Spring Boot, Docker, Kubernetes, AWS"
        → returns: ["Docker", "Kubernetes", "AWS"]
    """
    # Parse the job required skills string into a list
    if not job_required_skills:
        return []
    
    # Normalize both lists (lowercase, strip whitespace)
    student_set = set(s.lower().strip() for s in student_skills)
    required_list = [s.strip() for s in job_required_skills.split(",")]
    
    # Find skills the student doesn't have
    gaps = []
    for skill in required_list:
        if skill.lower().strip() not in student_set:
            gaps.append(skill)
    
    return gaps

# Enhanced analyze endpoint with gap computation
@app.post("/skill-gap-report")
async def skill_gap_report(payload: dict):
    """
    Returns a full skill gap analysis report:
    - Top 5 matching job roles
    - Percentage match for each role
    - List of missing skills for each role
    
    Payload: {"student_id": 5, "skills": ["Java", "Spring Boot", "MySQL"]}
    """
    skills = payload.get("skills", [])
    student_id = payload.get("student_id")
    
    analysis = analyze_student_skills(skills)
    
    # Add gap analysis for each matched job
    for job in analysis["matched_jobs"]:
        job["skill_gaps"] = compute_skill_gap(skills, job.get("required_skills", ""))
    
    return {
        "student_id": student_id,
        "student_skills": skills,
        "ai_analysis": analysis["matched_jobs"],
        "summary": f"Found {len(analysis['matched_jobs'])} matching job roles. "
                   f"Best match: {analysis['matched_jobs'][0]['match_score']}% with "
                   f"{len(analysis['matched_jobs'][0]['skill_gaps'])} skills to learn."
                   if analysis['matched_jobs'] else "No matching jobs found."
    }
```

---

## 14. Common Errors & How to Fix Them

| Error | Cause | Fix |
|---|---|---|
| `ModuleNotFoundError: langchain_community` | Old langchain install | `pip install -U langchain langchain-community` |
| `ONNX Runtime not found` | sentence-transformers needs ONNX | `pip install optimum[onnxruntime]` or ignore (CPU fallback works) |
| `pg: could not connect to server` | Wrong DB URL format, @ not encoded | Encode `@` as `%40` in the DB URL password |
| `vector extension not found` | pgvector not enabled | Run `CREATE EXTENSION IF NOT EXISTS vector;` in Supabase SQL editor |
| `column "embedding" is of type vector but expression is of type text` | Vector not cast properly | Add `::vector` cast: `%s::vector` in psycopg2 query |
| `Out of memory` on Ubuntu | Too many embeddings in memory | Reduce `BATCH_SIZE` from 32 to 8 |
| `dimension of vector must be 384` | Model output mismatch | Verify you're using `all-MiniLM-L6-v2` specifically |
| `SSL connection has been closed unexpectedly` | Supabase idle timeout | Reconnect to DB between batches; add `conn.autocommit = True` |
| `Killed` (process terminated) | RAM exhausted | Process only 500 rows at a time, run in multiple passes |

---

## 15. Phase 2 Complete Checklist

### Laptop (Browser Tasks)
- [ ] Enabled `pgvector` extension in Supabase SQL Editor
- [ ] Created `industry_job_descriptions` table with `vector(384)` column
- [ ] Created `ivfflat` index for fast similarity search
- [ ] Created `search_similar_jobs()` SQL function
- [ ] Verified table exists in Supabase Table Editor

### Ubuntu PC (Heavy Computation Tasks)
- [ ] Created `~/skillbridge-etl-pipeline/` directory
- [ ] Python virtual environment created and activated
- [ ] All dependencies installed (`langchain`, `sentence-transformers`, `pandas`, `psycopg2`, `tqdm`)
- [ ] Kaggle account set up and `kaggle.json` configured
- [ ] Job descriptions CSV downloaded to `data/` folder
- [ ] CSV columns identified (title, description, skills, company)
- [ ] `.env` file created with `SUPABASE_DB_URL`
- [ ] `ingest.py` created with full ETL pipeline code
- [ ] MiniLM model test run successful (`384 dimensions`)
- [ ] Full `python3 ingest.py` executed successfully
- [ ] 1000+ rows inserted into Supabase

### Verification (Laptop Browser)
- [ ] `SELECT COUNT(*)` shows 1000+ rows
- [ ] Vector dimensions confirmed as 384
- [ ] Similarity search SQL test returns ranked results

### Python AI Service Update (Laptop Code)
- [ ] `requirements.txt` updated with langchain + sentence-transformers
- [ ] `main.py` updated with embedding model loading on startup
- [ ] `analyze_student_skills()` function added
- [ ] `compute_skill_gap()` function added
- [ ] `/analyze-skills` endpoint tested
- [ ] `/skill-gap-report` endpoint tested

---

## Final Note
Once Phase 2 is complete, any student in SkillBridge can get:
1. Their **top 5 matching job roles** based on their current skills
2. A **match percentage** for each role (e.g., 82% match for Senior Java Developer)
3. A **specific list of missing skills** to learn to qualify for those roles

This transforms SkillBridge from a simple training management tool into an **AI-powered career navigation platform** — the kind of feature that would stand out in any senior SDE interview or hackathon.
