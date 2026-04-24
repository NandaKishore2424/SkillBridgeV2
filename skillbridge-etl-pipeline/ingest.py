"""
SkillBridge Phase 2 — RAG Data Ingestion Pipeline
====================================================
This script reads real job description data from a CSV file,
generates 384-dimensional vector embeddings for each description,
and stores them in the Supabase pgvector table.

Run on: Ubuntu PC (32GB RAM)
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
    raise ValueError("SUPABASE_DB_URL not found in .env file! Create a .env file with your connection string.")

# ─── 2. Load the HuggingFace Embedding Model ───────────────────────────────────
print("Loading MiniLM embedding model (downloading ~90MB the first time)...")
print("This downloads once and is cached forever after.")

embedder = HuggingFaceEmbeddings(
    model_name="sentence-transformers/all-MiniLM-L6-v2",
    model_kwargs={"device": "cpu"},   # Since you have an amazing Ryzen CPU
    encode_kwargs={"normalize_embeddings": True}  # needed for cosine similarity
)
print("✓ Model loaded successfully!\n")

# ─── 3. Load and Clean the CSV Data ────────────────────────────────────────────
print("Loading dataset...")

# Path to the dataset you downloaded and unzipped
CSV_FILE = "data/archive/DataAnalyst.csv"
df = pd.read_csv(CSV_FILE)

print(f"✓ Loaded {len(df)} rows. Columns: {df.columns.tolist()}\n")

# Based on the Kaggle dataset you provided, these are the exact column names:
TITLE_COL = "Job Title"
DESCRIPTION_COL = "Job Description"
COMPANY_COL = "Company Name"
LOCATION_COL = "Location"
SKILLS_COL = None  # This dataset doesn't have a dedicated skills column, we'll leave it blank

# Clean step 1: Remove rows with no description
df = df.dropna(subset=[DESCRIPTION_COL])
print(f"After dropping empty descriptions: {len(df)} rows")

# Clean step 2: Remove duplicate descriptions
df = df.drop_duplicates(subset=[DESCRIPTION_COL])
print(f"After removing duplicates: {len(df)} rows")

# Clean step 3: Truncate very long descriptions (MiniLM handles max 256 tokens)
# We take the first 2000 characters which is plenty of context for the AI
df[DESCRIPTION_COL] = df[DESCRIPTION_COL].str[:2000]

# Limit to 1500 rows to keep the run manageable (takes ~10 mins on your CPU)
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

# Batch processing — embed 32 texts at a time instead of 1 (much faster on Ryzen)
BATCH_SIZE = 32

for batch_start in tqdm(range(0, len(df), BATCH_SIZE), desc="Processing batches"):
    batch = df.iloc[batch_start : batch_start + BATCH_SIZE]
    
    # Build a combined text for each row in the batch
    # Combining title + description gives the model more context
    texts = []
    for _, row in batch.iterrows():
        title = str(row.get(TITLE_COL, ""))
        description = str(row[DESCRIPTION_COL])
        combined = f"Job Title: {title}\n\nDescription:\n{description}"
        texts.append(combined)
    
    try:
        # Generate embeddings for the entire batch at once (much faster)
        vectors = embedder.embed_documents(texts)
        
        # Insert each row + its vector into Supabase
        for i, (_, row) in enumerate(batch.iterrows()):
            title = str(row.get(TITLE_COL, "Unknown"))
            company = str(row.get(COMPANY_COL, ""))
            location = str(row.get(LOCATION_COL, ""))
            skills = "" # Left blank as per dataset
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
        
        # Commit every batch (save to DB progressively)
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
