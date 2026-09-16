package com.skillbridge.shared.messaging;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alert rules and the metrics they read, held to each other.
 *
 * <p>An alert whose expression names a metric that does not exist is valid PromQL.
 * Prometheus loads it, evaluates it, matches nothing, and never fires — and nothing
 * anywhere reports that. Renaming a Micrometer meter, or a tag value, is enough.
 * So every metric and every {@code label="value"} matcher in
 * {@code ops/prometheus/messaging-alerts.yml} is checked against what
 * {@link MessagingMetrics} actually registers, rendered by the real Prometheus
 * registry, naming conventions and all.
 *
 * <p>Not a PromQL parser: it strips strings, selectors and ranges, and treats the
 * identifiers left over that are not functions or keywords as metric names. That
 * is enough for the expressions this file uses, and a construct it misreads fails
 * loudly here rather than passing quietly.
 */
class MessagingAlertRulesTest {

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{([^}]*)})?\\s");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*(=~|!~|!=|=)\\s*\"([^\"]*)\"");
    private static final Pattern SELECTOR = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*\\{([^}]*)}");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");

    /** PromQL words that are not metrics. Anything unrecognised is treated as a metric, and so must exist. */
    private static final Set<String> NOT_METRICS = Set.of(
            "sum", "min", "max", "avg", "count", "increase", "rate", "irate", "delta",
            "absent", "absent_over_time", "max_over_time", "min_over_time", "avg_over_time",
            "by", "without", "on", "ignoring", "group_left", "group_right",
            "and", "or", "unless", "bool", "offset");

    private static Path root;
    private static List<Map<String, Object>> rules;
    /** Metric name -> the label sets it is emitted with. */
    private static final Map<String, List<Map<String, String>>> emitted = new HashMap<>();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        root = repositoryRoot();
        Map<String, Object> file = new Yaml().load(Files.readString(root.resolve("ops/prometheus/messaging-alerts.yml")));
        rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) file.get("groups")) {
            rules.addAll((List<Map<String, Object>>) group.get("rules"));
        }

        // Exactly what production registers, rendered by the registry production uses.
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        new MessagingMetrics(registry, none.getBeanProvider(JdbcTemplate.class),
                none.getBeanProvider(AmqpAdmin.class), false);

        for (String line : registry.scrape().split("\n")) {
            Matcher m = SAMPLE.matcher(line);
            if (line.startsWith("#") || !m.find()) {
                continue;
            }
            Map<String, String> labels = new HashMap<>();
            if (m.group(3) != null) {
                Matcher l = LABEL.matcher(m.group(3));
                while (l.find()) {
                    labels.put(l.group(1), l.group(3));
                }
            }
            emitted.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(labels);
        }
    }

    @Test
    @DisplayName("the rule file is not empty, and the registry emitted what the rules are about")
    void sanity() {
        assertThat(rules).hasSizeGreaterThanOrEqualTo(5);
        assertThat(emitted).containsKeys("outbox_events", "dead_letter_events", "messaging_queue_messages",
                "dead_letter_recorded_total", "dead_letter_record_failures_total");
    }

    @Test
    @DisplayName("every metric a rule names is one MessagingMetrics emits")
    void everyMetricExists() {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            for (String metric : metricsIn((String) rule.get("expr"))) {
                if (!emitted.containsKey(metric)) {
                    missing.add(rule.get("alert") + " -> " + metric);
                }
            }
        }
        assertThat(missing)
                .withFailMessage("These alerts name metrics nothing emits, so they can never fire:%n%s%n"
                        + "Emitted: %s", String.join("\n", missing), emitted.keySet())
                .isEmpty();
    }

    @Test
    @DisplayName("every label=value a rule matches on is a value some series carries")
    void everyLabelValueExists() {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Matcher selector = SELECTOR.matcher((String) rule.get("expr"));
            while (selector.find()) {
                String metric = selector.group(1);
                Matcher label = LABEL.matcher(selector.group(2));
                while (label.find()) {
                    if (!label.group(2).equals("=")) {
                        continue;
                    }
                    String key = label.group(1);
                    String value = label.group(3);
                    boolean present = emitted.getOrDefault(metric, List.of()).stream()
                            .anyMatch(labels -> value.equals(labels.get(key)));
                    if (!present) {
                        missing.add(rule.get("alert") + " -> " + metric + "{" + key + "=\"" + value + "\"}");
                    }
                }
            }
        }
        assertThat(missing)
                .withFailMessage("These matchers select no series, so their alerts can never fire:%n%s",
                        String.join("\n", missing))
                .isEmpty();
    }

    @Test
    @DisplayName("every rule has a severity, a summary and a runbook link that resolves")
    @SuppressWarnings("unchecked")
    void everyRuleIsActionable() throws Exception {
        Set<String> anchors = headingAnchors(Files.readString(root.resolve("docs/RUNBOOK_DLQ.md")));
        Set<String> names = new HashSet<>();
        for (Map<String, Object> rule : rules) {
            String alert = (String) rule.get("alert");
            assertThat(names.add(alert)).as("alert names are unique: %s", alert).isTrue();

            Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
            assertThat(labels).as("%s labels", alert).isNotNull();
            assertThat(labels.get("severity")).as("%s severity", alert).isIn("warning", "critical");
            assertThat(annotations).as("%s annotations", alert).isNotNull();
            assertThat((String) annotations.get("summary")).as("%s summary", alert).isNotBlank();

            String runbook = (String) annotations.get("runbook_url");
            assertThat(runbook).as("%s runbook_url", alert).startsWith("docs/RUNBOOK_DLQ.md#");
            String anchor = runbook.substring(runbook.indexOf('#') + 1);
            assertThat(anchors).as("%s links to #%s, which must be a heading in RUNBOOK_DLQ.md", alert, anchor)
                    .contains(anchor);
        }
    }

    @Test
    @DisplayName("the extractor finds metrics and ignores functions, labels and ranges")
    void extractorWorks() {
        assertThat(metricsIn("sum(increase(a_total{outcome=\"new\", x=~\"y|z\"}[10m])) by (le) > 0 or b != b"))
                .containsExactly("a_total", "b");
    }

    // ---------------------------------------------------------------- helpers

    static Set<String> metricsIn(String expr) {
        String stripped = expr
                .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"")   // string literals
                .replaceAll("\\{[^}]*}", " ")                        // label matchers
                .replaceAll("\\[[^]]*]", " ")                        // range selectors
                .replaceAll("\\b(by|without|on|ignoring)\\s*\\([^)]*\\)", " ");  // grouping labels
        Set<String> metrics = new LinkedHashSet<>();
        Matcher m = IDENTIFIER.matcher(stripped);
        while (m.find()) {
            String word = m.group();
            int next = m.end();
            while (next < stripped.length() && stripped.charAt(next) == ' ') {
                next++;
            }
            boolean isCall = next < stripped.length() && stripped.charAt(next) == '(';
            if (!isCall && !NOT_METRICS.contains(word)) {
                metrics.add(word);
            }
        }
        return metrics;
    }

    /** GitHub's anchor for each Markdown heading: lower-case, punctuation dropped, spaces to hyphens. */
    static Set<String> headingAnchors(String markdown) {
        Set<String> anchors = new HashSet<>();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("#")) {
                String text = line.replaceFirst("^#+\\s*", "").trim().toLowerCase(Locale.ROOT);
                anchors.add(text.replaceAll("[^a-z0-9 _-]", "").replace(' ', '-'));
            }
        }
        return anchors;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("ops/prometheus"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not find ops/prometheus above " + Path.of("").toAbsolutePath());
    }
}
