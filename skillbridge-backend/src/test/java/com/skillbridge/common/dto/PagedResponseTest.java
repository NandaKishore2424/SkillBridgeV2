package com.skillbridge.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire shape every list endpoint returns.
 *
 * <p>Twenty-five endpoints and their React callers depend on this, so the two
 * things worth pinning are the field <i>names</i> and the pager flags.
 *
 * <p>No Spring context: {@code PageImpl} is a plain object and the factory is a
 * pure function of it.
 */
class PagedResponseTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Page<String> page(List<String> content, int number, int size, long total) {
        return new PageImpl<>(content, PageRequest.of(number, size), total);
    }

    @Test
    @DisplayName("an empty result is first AND last, which arithmetic gets wrong")
    void emptyResultIsBothFirstAndLast() {
        // The case the class comment calls out. With no rows, totalPages is 0,
        // so a client deriving "last" from `page == totalPages - 1` computes
        // `0 == -1` and renders a Next button on an empty table. Sending the
        // flags removes the arithmetic, and this is the case that makes them
        // worth sending.
        PagedResponse<String> response = PagedResponse.from(page(List.of(), 0, 20, 0));

        assertThat(response.getTotalPages()).isZero();
        assertThat(response.isFirst()).isTrue();
        assertThat(response.isLast())
                .as("an empty result set has nowhere further to go")
                .isTrue();
        assertThat(response.getItems()).isEmpty();
    }

    @Test
    @DisplayName("the middle page is neither first nor last")
    void middlePageIsNeither() {
        PagedResponse<String> response = PagedResponse.from(page(List.of("b"), 1, 1, 3));

        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getTotalPages()).isEqualTo(3);
        assertThat(response.isFirst()).isFalse();
        assertThat(response.isLast()).isFalse();
    }

    @Test
    @DisplayName("a single full page is both first and last")
    void singlePageIsBoth() {
        PagedResponse<String> response = PagedResponse.from(page(List.of("a", "b"), 0, 20, 2));

        assertThat(response.isFirst()).isTrue();
        assertThat(response.isLast()).isTrue();
    }

    @Test
    @DisplayName("totalElements is the whole result set, not the rows on this page")
    void totalElementsCountsEverything() {
        // A component deriving a count from items.length reports "2 student(s)
        // found" for a college of 57. The number has to come from the server.
        PagedResponse<String> response = PagedResponse.from(page(List.of("a", "b"), 0, 2, 57));

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(57);
        assertThat(response.getTotalPages()).isEqualTo(29);
    }

    @Test
    @DisplayName("the mapper is applied to the content, and the metadata comes from the same page")
    void mappingKeepsContentAndMetadataInStep() {
        Page<String> source = page(List.of("a", "b"), 2, 2, 10);

        PagedResponse<String> response = PagedResponse.from(source, String::toUpperCase);

        assertThat(response.getItems()).containsExactly("A", "B");
        // Mapping through the factory rather than at the call site is what stops
        // the content coming from one page object and the metadata from another.
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getTotalElements()).isEqualTo(10);
    }

    @Test
    @DisplayName("an unsorted page says UNSORTED rather than an empty string")
    void unsortedIsNamed() {
        PagedResponse<String> response = PagedResponse.from(page(List.of("a"), 0, 20, 1));

        assertThat(response.getSort()).isEqualTo("UNSORTED");
    }

    @Test
    @DisplayName("a sorted page reports the sort it actually applied")
    void sortIsReported() {
        Page<String> sorted = new PageImpl<>(
                List.of("a"),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "startDate")),
                1);

        assertThat(PagedResponse.from(sorted).getSort()).contains("startDate", "DESC");
    }

    @Test
    @DisplayName("the collection serialises as `items`, which every screen reads")
    void collectionIsNamedItems() throws Exception {
        // Spring's own Page calls this `content`. Matching it would be tidier and
        // would silently break 25 screens, so the name is part of the contract
        // and this is the test that says so.
        String wire = json.writeValueAsString(PagedResponse.from(page(List.of("a"), 0, 20, 1)));

        assertThat(wire).contains("\"items\"");
        assertThat(wire).doesNotContain("\"content\"");
        // The pager flags have to reach the client too; @Data on a boolean names
        // the getter isFirst(), and a serialisation change could rename these.
        assertThat(wire).contains("\"first\"").contains("\"last\"")
                .contains("\"totalElements\"").contains("\"totalPages\"");
    }
}
