"""
Skills a job asks for, read from its description (decision #11, 2026-09-19).

Each case is a phrase taken from the real corpus, or the everyday-word trap it
stands for.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from skill_extraction import extract_skills  # noqa: E402


class ExtractSkills(unittest.TestCase):

    def test_finds_the_skills_a_description_names(self):
        self.assertEqual(extract_skills("High proficiency in Excel, SQL, Python and Tableau."),
                         ["python", "sql", "tableau", "excel"])

    def test_multi_word_skills(self):
        self.assertIn("power bi", extract_skills("Build dashboards in Power BI."))
        self.assertIn("machine learning", extract_skills("Apply machine learning to churn."))

    def test_word_boundaries(self):
        # "r" must not match inside React, "git" not inside GitHub, "sql" not inside MySQL.
        found = extract_skills("Experience with React, GitHub Actions and MySQL.")
        self.assertIn("react", found)
        self.assertIn("mysql", found)
        self.assertNotIn("r", found)
        self.assertNotIn("git", found)
        self.assertNotIn("sql", found)

    def test_r_is_a_language_but_not_in_r_and_d(self):
        self.assertIn("r", extract_skills("statistical packages (e.g. R, SAS, Stata)"))
        self.assertNotIn("r", extract_skills("Join our R&D team."))
        self.assertNotIn("r", extract_skills("Join our R & D team."))

    def test_excel_the_tool_but_not_the_verb(self):
        self.assertIn("excel", extract_skills("Strong handle of Excel preferred."))
        self.assertNotIn("excel", extract_skills("A team that demands you excel in a culture of teaching."))

    def test_nothing_in_nothing_out(self):
        self.assertEqual(extract_skills(""), [])
        self.assertEqual(extract_skills(None), [])


if __name__ == "__main__":
    unittest.main()
