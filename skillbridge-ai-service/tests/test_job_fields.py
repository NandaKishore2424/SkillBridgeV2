"""
What comes out of the `company` column, which holds two things.

The report names its top five matching employers, so whatever this returns is
read by a student on their dashboard.
"""

import unittest

import job_fields


class EmployerNameTest(unittest.TestCase):

    def test_drops_the_star_rating_the_scrape_glued_on(self):
        # The normal case: 1,315 of the 1,500 stored postings look like this.
        # HTML collapses the newline, so the dashboard read "Squarespace 3.4".
        self.assertEqual(job_fields.employer_name("Squarespace\n3.4"), "Squarespace")
        self.assertEqual(
            job_fields.employer_name("Vera Institute of Justice\n3.2"),
            "Vera Institute of Justice")

    def test_leaves_a_company_with_no_rating_alone(self):
        # The other 185. Nothing to strip, and stripping a name that contains no
        # newline must not shorten it.
        self.assertEqual(job_fields.employer_name("MT Global US INC"), "MT Global US INC")

    def test_keeps_the_whole_first_line(self):
        # A name with its own internal punctuation, spaces and digits is still
        # one name. Splitting on anything but the newline would cut it.
        self.assertEqual(
            job_fields.employer_name("Booz Allen Hamilton Inc. - 3rd Party\n3.9"),
            "Booz Allen Hamilton Inc. - 3rd Party")

    def test_trims_surrounding_whitespace(self):
        self.assertEqual(job_fields.employer_name("  Kelly  \n3.4"), "Kelly")

    def test_a_missing_company_renders_as_nothing_not_as_none(self):
        # The column is nullable. Passing None through to the report would put
        # the text "None" on a student's screen, or fail the event outright --
        # which it once did, slicing None in the log summary.
        self.assertEqual(job_fields.employer_name(None), "")
        self.assertEqual(job_fields.employer_name(""), "")
        self.assertEqual(job_fields.employer_name("   "), "")

    def test_a_rating_with_no_name_is_not_passed_off_as_a_name(self):
        # Nothing in the corpus looks like this today. If a row ever did, an
        # employer called "4.2" is worse than an employer called nothing.
        self.assertEqual(job_fields.employer_name("\n4.2"), "")


if __name__ == "__main__":
    unittest.main()
