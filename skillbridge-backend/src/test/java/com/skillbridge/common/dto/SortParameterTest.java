package com.skillbridge.common.dto;

import com.skillbridge.common.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The allowlist is the security-relevant half of {@code ?sort=}, so it is
 * tested rather than assumed.
 *
 * <p>Pure logic, no Spring context: these run on every build, not only when
 * {@code DATABASE_URL} is set.
 */
class SortParameterTest {

    private static final Map<String, String> ALLOWED =
            SortParameter.allow("fullName", "fullName", "email", "user.email");

    private static final Sort FALLBACK = Sort.by("createdAt");

    @Test
    @DisplayName("no sort parameter falls back to the endpoint's default")
    void blankFallsBack() {
        assertThat(SortParameter.parse(null, ALLOWED, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(SortParameter.parse("", ALLOWED, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(SortParameter.parse("   ", ALLOWED, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("a bare field sorts ascending")
    void bareFieldIsAscending() {
        assertThat(SortParameter.parse("fullName", ALLOWED, FALLBACK))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "fullName"));
    }

    @Test
    @DisplayName("direction is honoured and case-insensitive")
    void directionIsParsed() {
        assertThat(SortParameter.parse("fullName,desc", ALLOWED, FALLBACK))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "fullName"));
        assertThat(SortParameter.parse("fullName,DESC", ALLOWED, FALLBACK))
                .isEqualTo(Sort.by(Sort.Direction.DESC, "fullName"));
        assertThat(SortParameter.parse(" fullName , Asc ", ALLOWED, FALLBACK))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "fullName"));
    }

    @Test
    @DisplayName("the API name is translated to the entity path, not passed through")
    void apiNameMapsToEntityPath() {
        // The client says "email"; the query needs "user.email". Leaking the
        // entity path into the API would tie the wire format to the schema.
        assertThat(SortParameter.parse("email", ALLOWED, FALLBACK))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "user.email"));
    }

    @Test
    @DisplayName("a field outside the allowlist is refused, and the message says what works")
    void unknownFieldIsRejected() {
        assertThatThrownBy(() -> SortParameter.parse("passwordHash", ALLOWED, FALLBACK))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("passwordHash")
                .hasMessageContaining("fullName")
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("an entity path is refused even though the query would accept it")
    void entityPathIsNotAnApiName() {
        // "user.email" is a legal property path and would work if passed
        // through. It is still not a name this endpoint publishes, and
        // accepting it would let a caller walk the object graph.
        assertThatThrownBy(() -> SortParameter.parse("user.email", ALLOWED, FALLBACK))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("an unparseable direction is refused rather than silently ascending")
    void badDirectionIsRejected() {
        assertThatThrownBy(() -> SortParameter.parse("fullName,sideways", ALLOWED, FALLBACK))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sideways");
    }

    @Test
    @DisplayName("a trailing comma means the field with no direction, not an error")
    void trailingCommaIsTolerated() {
        assertThat(SortParameter.parse("fullName,", ALLOWED, FALLBACK))
                .isEqualTo(Sort.by(Sort.Direction.ASC, "fullName"));
    }

    @Test
    @DisplayName("allow() rejects an odd number of arguments at construction")
    void allowRequiresPairs() {
        assertThatThrownBy(() -> SortParameter.allow("fullName"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the allowlist keeps declaration order, so error messages read sensibly")
    void allowlistIsOrdered() {
        assertThat(SortParameter.allow("b", "b", "a", "a", "c", "c").keySet())
                .containsExactly("b", "a", "c");
    }
}
