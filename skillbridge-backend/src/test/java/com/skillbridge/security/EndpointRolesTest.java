package com.skillbridge.security;

import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may call what, as one list, checked against a file in the repository.
 *
 * <p>Authorization was 118 {@code @PreAuthorize} strings in twelve spellings,
 * four of them pairs that meant the same set of roles written two ways. A
 * missing one is invisible: the endpoint simply admits anybody who is signed
 * in, and no test says otherwise. So the roles are now named annotations
 * ({@code @CollegeAdminOnly} and the rest), and this test writes the table they
 * produce and compares it with {@code src/test/resources/endpoint-roles.txt}.
 *
 * <p>The point is the diff. Adding an endpoint, or changing who may reach one,
 * fails until the file is updated in the same commit, where a reviewer sees
 * exactly which line moved -- including a new endpoint landing on PUBLIC.
 *
 * <p>To update after a deliberate change:
 * {@code cp skillbridge-backend/target/endpoint-roles.actual.txt
 * skillbridge-backend/src/test/resources/endpoint-roles.txt}
 */
@SpringBootTest
@IntegrationTest
class EndpointRolesTest {

    private static final Path EXPECTED = Path.of("src/test/resources/endpoint-roles.txt");
    private static final Path ACTUAL = Path.of("target/endpoint-roles.actual.txt");

    /** Secured by URL rules in SecurityConfig, not by annotations. */
    private static final List<String> NOT_OURS = List.of("/actuator", "/v3/api-docs", "/swagger-ui", "/error");

    private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

    // Named, because actuator contributes a second RequestMappingHandlerMapping.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("every endpoint admits exactly the roles the checked-in table says")
    void rolesMatchTheTable() throws IOException {
        List<String> table = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            // Resolved through meta-annotations, so @CollegeAdminOnly counts.
            PreAuthorize rule = entry.getValue().getMethodAnnotation(PreAuthorize.class);
            String roles = rolesOf(rule);
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (NOT_OURS.stream().anyMatch(pattern::startsWith)) {
                    continue;
                }
                if (info.getMethodsCondition().getMethods().isEmpty()) {
                    table.add("%-7s %-64s %s".formatted("ANY", pattern, roles));
                }
                info.getMethodsCondition().getMethods()
                        .forEach(method -> table.add("%-7s %-64s %s".formatted(method.name(), pattern, roles)));
            }
        }
        table.sort(String::compareTo);

        Files.createDirectories(ACTUAL.getParent());
        Files.writeString(ACTUAL, String.join("\n", table) + "\n", StandardCharsets.UTF_8);

        assertThat(table).as("a run that finds no endpoints proves nothing").hasSizeGreaterThan(100);
        assertThat(table)
                .withFailMessage("""
                        The endpoints or their roles changed.

                        Read the difference below: a line that moved to PUBLIC, or to a wider \
                        set of roles, is a hole. If it is deliberate, copy the new table over \
                        the old one in the same commit:

                          cp skillbridge-backend/%s skillbridge-backend/%s

                        %s""", ACTUAL, EXPECTED, difference(table, expectedTable()))
                .isEqualTo(expectedTable());
    }

    /**
     * The roles an expression admits, or NONE when the method carries no role
     * annotation. NONE is not "open to the world": SecurityConfig still decides,
     * and everything outside its permitAll list needs a signed-in caller. It
     * means only that this endpoint does not narrow by role.
     */
    private static String rolesOf(PreAuthorize rule) {
        if (rule == null) {
            return "NONE";
        }
        TreeSet<String> roles = new TreeSet<>();
        Matcher matcher = ROLE.matcher(rule.value());
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles.isEmpty() ? rule.value() : String.join(",", roles);
    }

    /** The checked-in table, without its explanatory header. */
    private static List<String> expectedTable() throws IOException {
        return Files.readAllLines(EXPECTED).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    private static String difference(List<String> actual, List<String> expected) {
        List<String> lines = new ArrayList<>();
        actual.stream().filter(line -> !expected.contains(line)).forEach(line -> lines.add("+ " + line));
        expected.stream().filter(line -> !actual.contains(line)).forEach(line -> lines.add("- " + line));
        return String.join("\n", lines);
    }
}
