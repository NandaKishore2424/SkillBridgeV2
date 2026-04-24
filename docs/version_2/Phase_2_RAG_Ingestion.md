# Phase 2: Real Data Ingestion & Vector Construction (RAG)

## Overview
**Goal:** We need massive amounts of real-world Job Descriptions (JDs) embedded into mathematics (Vectors) to build a personalized gap analysis. Because computing embeddings is highly CPU intensive, **this entire phase runs strictly on your 32GB Ubuntu PC as a one-time operation.** 

**Hardware Execution Splitting:**
- **Ubuntu PC:** ✅ ALL heavy data scraping and model embedding happens here.
- **Demo Laptop:** ❌ DO NOT run embedding scripts here. The laptop will only *read* the data from Supabase later.

---

## 🛑 What NOT to do
1. **DO NOT** run HuggingFace embedding models on the laptop.
2. **DO NOT** store the vector data locally on Ubuntu. We must push the vectors directly to your cloud Supabase instance so the laptop can access them globally.

---

## ✅ Task Breakdown

### 1. Cloud Database Setup (Laptop - Browser)
- [ ] Log in to your existing Supabase project.
- [ ] Run a SQL query in the Supabase SQL editor to enable pgvector:
  ```sql
  CREATE EXTENSION IF NOT EXISTS vector;
  ```
- [ ] Create an `industry_job_descriptions` table ensuring it contains a column for embeddings:
  ```sql
  CREATE TABLE industry_job_descriptions (
      id BIGSERIAL PRIMARY KEY,
      title VARCHAR(255),
      company VARCHAR(255),
      raw_description TEXT,
      embedding vector(384) -- 384 dimensions for standard MiniLM models
  );
  ```

### 2. ETL Script Setup Environment (UBUNTU PC)
- [ ] On the Ubuntu PC, create a new directory: `mkdir skillbridge-etl-pipeline`.
- [ ] Initialize Python and install heavy AI dependencies:
  ```bash
  python3 -m venv venv
  source venv/bin/activate
  pip install langchain sentence-transformers psycopg2-binary supabase pandas
  ```

### 3. Data Extraction (UBUNTU PC)
- [ ] Obtain a real-world dataset. Go to Kaggle (e.g., "Data Scientist / Software Engineer Job Descriptions"). Download the `.csv` file.
- [ ] Move the `.csv` into your `skillbridge-etl-pipeline` folder.

### 4. Vector Embedding & Loading (UBUNTU PC)
- [ ] Write `ingest.py`. 
- [ ] In the script, use `pandas` to read the Kaggle CSV.
- [ ] Load the open-source HuggingFace model locally:
  ```python
  from langchain.embeddings import HuggingFaceEmbeddings
  embeddings = HuggingFaceEmbeddings(model_name="all-MiniLM-L6-v2")
  ```
- [ ] Loop over the dataset. For every row, convert the `raw_description` text into a 384-dimension vector.
- [ ] Securely connect to your Supabase PostgreSQL instance using `psycopg2` and `INSERT` the title, company, text, and vector mathematical array into the database.
- [ ] **Execution:** Run `python ingest.py`. Wait 10-20 minutes for your Ubuntu 32GB RAM to chew through the data and upload it to the cloud.

### 5. Verification (Laptop - Browser)
- [ ] Open the Supabase Dashboard on your laptop. Verify that the `industry_job_descriptions` table now contains 1,000+ rows, with the `embedding` column populated with long arrays of decimal numbers.
