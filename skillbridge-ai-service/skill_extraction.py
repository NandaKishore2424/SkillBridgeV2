"""
Which known skills a job asks for, read from its description.

Every one of the 1,500 stored jobs has required_skills = '' (the ETL never
filled it), so the skill-gap report listed matched jobs and no missing skills.
Decided 2026-09-19 (HANDOVER open question #11): extract at analysis time from
raw_description, rather than backfilling required_skills once. The stored
corpus -- the only copy of the embeddings -- is left untouched, and a better
extractor applies to every job the next time it is matched.

Measured over the 1,500 descriptions on 2026-09-19, with the exceptions below:
915 name at least one known skill (median 1, most 13); sql 577, excel 356,
python 214, tableau 212. The other 585 name none, so a report's missing skills
come from whichever of its top jobs do.

No LangChain or model imports here, so the fast test tier can import it.
"""

import re

KNOWN_SKILLS: list[str] = [
    # Languages
    "python", "sql", "r", "java", "scala", "julia", "bash",
    # Databases
    "postgresql", "mysql", "mongodb", "redis", "cassandra", "snowflake", "bigquery",
    # Data & Analytics
    "pandas", "numpy", "tableau", "power bi", "excel", "spark", "hadoop", "dbt",
    "airflow", "kafka", "etl",
    # ML/AI
    "machine learning", "deep learning", "tensorflow", "pytorch", "scikit-learn",
    "nlp", "llm", "langchain", "huggingface",
    # Cloud & DevOps
    "aws", "azure", "gcp", "docker", "kubernetes", "git", "linux",
    # Web/Backend (since SkillBridge students may have these)
    "spring boot", "react", "node.js", "fastapi", "rest api",
    # Soft/Analytics
    "statistics", "data visualization", "a/b testing", "regression",
]


# A skill that is also an everyday word, and the contexts in which it is only
# the word. Each measured on the corpus: "excel" was a verb ("you excel in")
# in 8 of the 364 jobs that matched it, and "r" was only "R&D" in 4 of 126.
NOT_A_SKILL_WHEN_FOLLOWED_BY: dict[str, str] = {
    "excel": r"\s+(in|at|as|with)\b",
    "r": r"\s*&\s*d\b",
}

_PATTERNS = {
    skill: re.compile(
        r"\b" + re.escape(skill) + r"\b"
        + (f"(?!{NOT_A_SKILL_WHEN_FOLLOWED_BY[skill]})" if skill in NOT_A_SKILL_WHEN_FOLLOWED_BY else ""))
    for skill in KNOWN_SKILLS
}


def extract_skills(text: str) -> list[str]:
    """
    The known skills a text mentions, in KNOWN_SKILLS order, each once.

    Word-bounded and case-insensitive, so "r" does not match inside "React" and
    "git" does not match "GitHub".
    """
    lower = (text or "").lower()
    return [skill for skill, pattern in _PATTERNS.items() if pattern.search(lower)]
