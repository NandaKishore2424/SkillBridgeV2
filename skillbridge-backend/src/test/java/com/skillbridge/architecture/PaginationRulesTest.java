package com.skillbridge.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.jpa.repository.Query;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Conventions about paging, enforced instead of remembered.
 *
 * <p>Every rule here exists because the thing it forbids has already happened
 * in this codebase, and in each case nothing failed at the time:
 *
 * <ul>
 *   <li>Every list endpoint passed {@code ?size=} straight to
 *       {@code PageRequest.of}, so {@code ?size=1000000} was honoured — one
 *       request that makes Postgres materialise a whole table. Fixed by routing
 *       every page request through {@link com.skillbridge.common.dto.Pagination},
 *       which clamps. Nothing stopped the next controller reaching for
 *       {@code PageRequest.of} again, which is what rule one is.</li>
 *   <li>Controllers returned JPA entities directly, serialising lazy
 *       associations and coupling the wire format to the schema.</li>
 *   <li>A paged {@code @Query} containing a fetch join needs its own
 *       {@code countQuery}; Spring Data cannot derive one, and the failure is a
 *       startup-time parse error at best and a wrong total at worst.</li>
 * </ul>
 *
 * <p>These are unit tests over bytecode: no Spring context, no database, so they
 * run on every build rather than only when {@code DATABASE_URL} is set. That
 * matters — a guard that only runs in the gated profile is a guard that will not
 * run in CI on the day it is needed.
 */
class PaginationRulesTest {

    private static final String BASE = "com.skillbridge";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("page requests are built through Pagination, which clamps the size")
    void pageRequestsGoThroughPagination() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("com.skillbridge.common.dto.Pagination")
                .should().callMethod(org.springframework.data.domain.PageRequest.class, "of", int.class, int.class)
                .orShould().callMethod(org.springframework.data.domain.PageRequest.class, "of",
                        int.class, int.class, org.springframework.data.domain.Sort.class)
                .because("PageRequest.of takes the client's size unchecked; Pagination.of clamps it "
                        + "to 100. ?size=1000000 was honoured until 2026-09-06");

        rule.check(production);
    }

    @Test
    @DisplayName("controllers do not call an unpaginated findAll()")
    void controllersDoNotCallUnpaginatedFindAll() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().callMethodWhere(new DescribedPredicate<>("an unpaginated findAll()") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                        return call.getTarget().getName().equals("findAll")
                                && call.getTarget().getRawParameterTypes().isEmpty();
                    }
                })
                .because("findAll() with no Pageable reads the whole table into the heap; "
                        + "the endpoint looks fine until the table grows");

        rule.check(production);
    }

    @Test
    @DisplayName("controllers do not return JPA entities")
    void controllersReturnDtosNotEntities() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
                .and().arePublic()
                .should(notExposeAnEntity())
                .because("an entity on the wire serialises lazy associations, leaks column names "
                        + "and couples the response to the schema");

        rule.check(production);
    }

    @Test
    @DisplayName("a paged @Query that fetch-joins declares its own countQuery")
    void pagedFetchJoinQueriesDeclareACountQuery() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Repository")
                .should(declareACountQueryWhenPagingAFetchJoin())
                .because("Spring Data cannot derive a count from a query containing a fetch join");

        rule.check(production);
    }

    @Test
    @DisplayName("every collection association carries a @BatchSize")
    void collectionsAreBatchFetched() {
        ArchRule rule = fields()
                .that().areAnnotatedWith(OneToMany.class)
                .or().areAnnotatedWith(ManyToMany.class)
                .or().areAnnotatedWith(ElementCollection.class)
                .should().beAnnotatedWith(BatchSize.class)
                .because("a lazy collection touched in a loop is one query per row; @BatchSize "
                        + "makes that N/size+1 instead. It is the net for the fetch join somebody "
                        + "forgot, and it costs nothing to have");

        rule.check(production);
    }

    // ------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------

    /**
     * Fails a controller method whose return type is, or wraps, an {@code @Entity}.
     *
     * <p>Unwraps one level of generics, which is enough for the shapes that
     * actually occur here — {@code ResponseEntity<T>}, {@code PagedResponse<T>},
     * {@code List<T>} — and is why the check works on
     * {@code ResponseEntity<List<Batch>>} rather than only on a bare {@code Batch}.
     */
    private static ArchCondition<JavaMethod> notExposeAnEntity() {
        return new ArchCondition<>("not return a JPA entity") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getReturnType().getAllInvolvedRawTypes().stream()
                        .filter(type -> type.isAnnotatedWith(Entity.class))
                        .forEach(entity -> events.add(SimpleConditionEvent.violated(method,
                                "%s returns the entity %s".formatted(
                                        method.getFullName(), entity.getSimpleName()))));
            }
        };
    }

    /**
     * Fails a repository method that returns a {@link Page} from a {@code @Query}
     * containing {@code join fetch} without supplying {@code countQuery}.
     *
     * <p>Spring Data derives the count query by rewriting the main one, and it
     * cannot rewrite a fetch join. Depending on the shape you get a startup
     * failure, or — worse — a count over the joined rows, which silently reports
     * the wrong {@code totalElements} and therefore the wrong page count.
     */
    private static ArchCondition<JavaMethod> declareACountQueryWhenPagingAFetchJoin() {
        return new ArchCondition<>("declare a countQuery when paging a fetch join") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!method.getRawReturnType().isAssignableTo(Page.class)) {
                    return;
                }
                method.tryGetAnnotationOfType(Query.class).ifPresent(query -> {
                    boolean fetchJoins = query.value().toLowerCase().contains("join fetch");
                    if (fetchJoins && query.countQuery().isBlank()) {
                        events.add(SimpleConditionEvent.violated(method,
                                "%s pages a fetch join without a countQuery".formatted(
                                        method.getFullName())));
                    }
                });
            }
        };
    }
}
