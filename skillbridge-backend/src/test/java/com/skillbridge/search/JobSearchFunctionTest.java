package com.skillbridge.search;

import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code search_similar_jobs}, the SQL function behind the AI service's
 * skill-gap search (V4).
 *
 * <p>Until V4 it ranked by {@code <->} (L2 distance) and reported {@code 1 - L2}
 * as a similarity. Nothing failed: the function returned zero rows for every
 * realistic query, and the service logged "0 matching jobs". So this test pins
 * the two properties that were silently wrong:
 *
 * <ul>
 *   <li><b>The number is a cosine similarity</b>, so the threshold means what it
 *       says. Checked with hand-built unit vectors whose cosines are known
 *       exactly.</li>
 *   <li><b>The index can serve the query.</b> {@code idx_job_embedding} is
 *       {@code vector_cosine_ops}, which only serves {@code <=>}. With 1,500 rows
 *       the planner rightly prefers a sequential scan, so a plain {@code EXPLAIN}
 *       proves nothing (trap 7); {@code enable_seqscan = off} asks whether the
 *       index is <i>usable</i>, which the old operator made impossible.</li>
 * </ul>
 *
 * <p>{@code @DataJpaTest} rolls each test back, so the rows never outlive it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@IntegrationTest
class JobSearchFunctionTest {

    private static final int DIMENSIONS = 384;
    private static final double INV_SQRT2 = 1 / Math.sqrt(2);

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // Four unit vectors at known angles to the query e0.
        insertJob("identical", unit(0, 1.0, 1, 0.0));          // cos  1.000
        insertJob("diagonal", unit(0, INV_SQRT2, 1, INV_SQRT2)); // cos  0.707
        insertJob("orthogonal", unit(0, 0.0, 1, 1.0));          // cos  0.000
        insertJob("opposite", unit(0, -1.0, 1, 0.0));           // cos -1.000

        // An ivfflat scan with the default single probe may skip lists. Probing
        // every list makes the answer exact whichever plan the planner picks.
        jdbc.execute("SET LOCAL ivfflat.probes = 100");
    }

    @Test
    @DisplayName("similarity is 1 - cosine distance, and the threshold filters on it")
    void similarityIsCosine() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title, similarity FROM search_similar_jobs(?::vector, 0.5, 10)",
                vector(unit(0, 1.0, 1, 0.0)));

        // Under the V1 definition 1 - L2 gives 1.0 and 0.235 here, so the
        // diagonal row fell below 0.5 and only one row came back.
        assertThat(rows).extracting(r -> r.get("title"))
                .as("rows above cosine 0.5, nearest first")
                .containsExactly("identical", "diagonal");
        assertThat((Double) rows.get(0).get("similarity")).isCloseTo(1.0, within(1e-6));
        assertThat((Double) rows.get(1).get("similarity")).isCloseTo(INV_SQRT2, within(1e-6));
    }

    @Test
    @DisplayName("match_count limits the result, and ordering runs from most to least similar")
    void orderedAndLimited() {
        // This one passes under V1 too: for unit vectors L2 and cosine rank
        // identically. It pins the contract, not the fix.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title FROM search_similar_jobs(?::vector, -2, 3)",
                vector(unit(0, 1.0, 1, 0.0)));

        assertThat(rows).extracting(r -> r.get("title"))
                .containsExactly("identical", "diagonal", "orthogonal");
    }

    @Test
    @DisplayName("each match carries its description, which the AI service reads skills from (V8)")
    void returnsDescription() {
        // required_skills is empty on every stored job; without the description
        // the skill-gap report had no missing skills to show (decision #11).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title, raw_description FROM search_similar_jobs(?::vector, 0.9, 1)",
                vector(unit(0, 1.0, 1, 0.0)));

        assertThat(rows).singleElement().satisfies(r -> assertThat(r.get("raw_description")).isEqualTo("fixture"));
    }

    @Test
    @DisplayName("the function's operator matches idx_job_embedding's operator class")
    void cosineIndexIsUsable() {
        jdbc.execute("SET LOCAL enable_seqscan = off");

        // The vector is a literal on purpose. A SQL function is inlined into the
        // caller's plan only when its arguments are simple; given a subquery it
        // stays an opaque "Function Scan" and EXPLAIN cannot see inside it. The AI
        // service passes a literal (psycopg2 interpolates client-side), so this is
        // the plan it gets.
        String plan = jdbc.queryForList(
                        "EXPLAIN (COSTS OFF) SELECT * FROM search_similar_jobs('"
                                + vector(unit(0, 1.0, 1, 0.0)) + "'::vector, 0.3, 5)",
                        String.class)
                .stream().collect(Collectors.joining("\n"));

        assertThat(plan)
                .as("with sequential scans off, the search must be able to use the cosine index:%n%s", plan)
                .contains("Index Scan using idx_job_embedding");
    }

    private void insertJob(String title, double[] embedding) {
        jdbc.update("INSERT INTO industry_job_descriptions (title, company, required_skills, raw_description, embedding) "
                        + "VALUES (?, 'JobSearchFunctionTest', '', 'fixture', ?::vector)",
                title, vector(embedding));
    }

    /** A 384-dimension vector with two components set and the rest zero. */
    private static double[] unit(int i, double a, int j, double b) {
        double[] v = new double[DIMENSIONS];
        v[i] = a;
        v[j] = b;
        return v;
    }

    private static String vector(double[] v) {
        StringBuilder s = new StringBuilder("[");
        for (int k = 0; k < v.length; k++) {
            s.append(k == 0 ? "" : ",").append(v[k]);
        }
        return s.append(']').toString();
    }
}
