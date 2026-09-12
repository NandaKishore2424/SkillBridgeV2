package com.skillbridge.architecture;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.skillbridge.common.cache.L1CacheConfig;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

import java.time.Duration;
import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conventions about caching, enforced rather than remembered.
 *
 * <p>Bytecode rules, no Spring context and no database, so they run on every
 * build rather than only when {@code DATABASE_URL} is set — the same reason
 * {@link PaginationRulesTest} is written this way.
 *
 * <p>The first rule is the one that matters. <b>A cache key without a tenant is
 * a cross-tenant leak</b>, and it is one missing token in a SpEL string that no
 * amount of reading catches: {@code @Cacheable(key = "'stats'")} on a dashboard
 * is syntactically perfect and serves college B college A's numbers. Phase 06
 * § 3.3 says the same thing and then says "no test will catch it unless you
 * write one". This is that test.
 */
class CacheRulesTest {

    private static final String BASE = "com.skillbridge";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("a @Cacheable on tenant data names a tenant or a user in its key")
    void tenantScopedCachesAreKeyedByTenant() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(Cacheable.class)
                .should(nameTheirTenant())
                .because("""
                        a cache key without a tenant is a cross-tenant leak. The key is \
                        one SpEL string and the bug is one missing token in it, so the \
                        only thing that catches it is a rule. A cache whose contents are \
                        genuinely the same for every tenant belongs in \
                        L1CacheConfig.GLOBAL_CACHES, which is a deliberate declaration \
                        rather than a default.""");

        rule.check(production);
    }

    private static ArchCondition<JavaMethod> nameTheirTenant() {
        return new ArchCondition<>("name a tenant, a user or a globally unique id in the cache key") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Cacheable annotation = method.reflect().getAnnotation(Cacheable.class);
                String[] names = annotation.cacheNames().length > 0
                        ? annotation.cacheNames() : annotation.value();

                boolean global = names.length > 0
                        && Arrays.stream(names).allMatch(L1CacheConfig.GLOBAL_CACHES::contains);
                if (global) {
                    return;
                }

                // Either a key generator that puts the tenant in structurally, or
                // a key that names the scope explicitly. Anything else is a key
                // shared across tenants.
                String key = annotation.key();
                boolean scoped = !annotation.keyGenerator().isBlank()
                        || key.contains("collegeId")
                        || key.contains("userId")
                        || key.contains("batchId");

                if (!scoped) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " caches " + Arrays.toString(names)
                                    + " under key \"" + key + "\" with no tenant, user or batch in it"));
                }
            }
        };
    }

    @Test
    @DisplayName("nothing writes a college outside the service that evicts the cached list")
    void collegeWritesGoThroughTheEvictingService() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(
                        "com.skillbridge.college.service.CollegeDirectoryService")
                .should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "CollegeRepository.save") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                        return call.getTarget().getOwner().isAssignableTo(
                                        com.skillbridge.college.repository.CollegeRepository.class)
                                && call.getTarget().getName().equals("save");
                    }
                })
                .because("""
                        the public registration form reads a cached active-college list. \
                        A write that does not go through CollegeDirectoryService leaves a \
                        deactivated college on that form for up to thirty minutes, and \
                        nothing fails -- the list is simply wrong and nobody is looking \
                        at it.""");

        rule.check(production);
    }

    @Test
    @DisplayName("every syllabus write evicts the curriculum cache")
    void everySyllabusWriteEvicts() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat()
                .haveFullyQualifiedName("com.skillbridge.syllabus.service.SyllabusService")
                .and().arePublic()
                .and(new com.tngtech.archunit.base.DescribedPredicate<JavaMethod>("are writes") {
                    @Override
                    public boolean test(JavaMethod method) {
                        org.springframework.transaction.annotation.Transactional tx =
                                method.reflect().getAnnotation(
                                        org.springframework.transaction.annotation.Transactional.class);
                        return tx != null && !tx.readOnly();
                    }
                })
                .should().beAnnotatedWith(org.springframework.cache.annotation.CacheEvict.class)
                .because("""
                        a curriculum write that does not evict leaves every student and                         trainer on that batch reading a syllabus fifteen minutes out of                         date, and nothing reports it -- the endpoint answers 200 with the                         tree as it was. Most of these methods are addressed by module,                         sub-module or topic id and never see a batch id, which is why the                         eviction is allEntries and why forgetting one is easy.""");

        rule.check(production);
    }

    @Test
    @DisplayName("every method that deactivates an account also revokes its tokens")
    void deactivationRevokes() {
        ArchRule rule = methods()
                .that(new com.tngtech.archunit.base.DescribedPredicate<JavaMethod>(
                        "set User.isActive") {
                    @Override
                    public boolean test(JavaMethod method) {
                        return method.getMethodCallsFromSelf().stream().anyMatch(call ->
                                call.getTarget().getOwner().isAssignableTo(
                                        com.skillbridge.auth.entity.User.class)
                                        && call.getTarget().getName().equals("setIsActive"));
                    }
                })
                .should(alsoCall("com.skillbridge.auth.service.TokenRevocationService", "revoke"))
                .because("""
                        the request path reads the token's claims, not the user, so an                         account deactivated without revoking keeps working until its                         access token expires. Nothing fails and nothing logs -- the                         deactivated user simply carries on for up to fifteen minutes.""");

        rule.check(production);
    }

    /**
     * Names the method, not just the class.
     *
     * <p>The first version of this rule asked only whether the owning class was
     * called at all, and stayed green when {@code revoke} was deleted from a
     * deactivation path — the surviving {@code restore} on the reactivation
     * branch satisfied it. A rule that passes against the bug it names is worse
     * than no rule, because it is read as coverage.
     */
    private static ArchCondition<JavaMethod> alsoCall(String ownerFqn, String methodName) {
        return new ArchCondition<>("also call " + ownerFqn + "." + methodName) {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean calls = method.getMethodCallsFromSelf().stream().anyMatch(call ->
                        call.getTarget().getOwner().getName().equals(ownerFqn)
                                && call.getTarget().getName().equals(methodName));
                if (!calls) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " changes User.isActive without calling "
                                    + ownerFqn + "." + methodName));
                }
            }
        };
    }

    @Test
    @DisplayName("refreshAfterWrite cannot be used here, and that is why it is absent")
    void refreshAfterWriteNeedsALoaderThatAnnotationCachingCannotSupply() {
        // Phase 06 § 2.1 asks for refreshAfterWrite so one thread reloads while
        // everyone else keeps the old value. It is a good idea and it does not
        // compose with @Cacheable, which populates through Cache.get(key,
        // Callable) and never registers a per-key loader. Asserted rather than
        // asserted-in-a-comment: this project has already shipped a config file
        // that did nothing because nothing loaded it (gotcha 9), and "this option
        // would have no effect" is exactly that shape of claim.
        assertThatThrownBy(() -> Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .refreshAfterWrite(Duration.ofMinutes(25))
                .build())
                .as("if this stops throwing, Caffeine has gained loader-less refresh "
                        + "and L1CacheConfig should be revisited")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("every declared cache name is one the config actually registers")
    void everyCacheNameIsRegistered() {
        // A @Cacheable naming a cache the manager does not know about is not an
        // error at startup; Spring's CaffeineCacheManager creates it on demand
        // with default settings, so a typo silently produces an unbounded,
        // never-expiring, unmonitored cache under a name nobody will ever evict.
        java.util.Set<String> registered = java.util.Set.of(
                L1CacheConfig.ACTIVE_COLLEGES,
                L1CacheConfig.CURRICULUM);

        java.util.List<String> unknown = production.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Cacheable.class))
                .map(m -> m.reflect().getAnnotation(Cacheable.class))
                .flatMap(a -> Arrays.stream(
                        a.cacheNames().length > 0 ? a.cacheNames() : a.value()))
                .filter(name -> !registered.contains(name))
                .toList();

        assertThat(unknown)
                .as("a cache name L1CacheConfig does not register gets created on demand, "
                        + "unbounded and unmonitored, rather than failing")
                .isEmpty();
    }

    @Test
    @DisplayName("GLOBAL_CACHES is a short, deliberate list")
    void globalCachesIsDeclaredNotDefaulted() {
        assertThat(L1CacheConfig.GLOBAL_CACHES)
                .as("""
                    Each name here is an assertion that the data is identical for every \
                    tenant, and it switches off the tenant-key rule for that cache. If \
                    this list has grown, check that each addition was a decision.""")
                .containsExactly(L1CacheConfig.ACTIVE_COLLEGES);
    }
}
