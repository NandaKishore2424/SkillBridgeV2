/**
 * Root package. Exists to hold the one global {@code @FilterDef}.
 *
 * <p>Hibernate treats a filter definition as global, not per-entity, and throws
 * {@code AnnotationException: Multiple '@FilterDef' annotations define a filter
 * named 'collegeFilter'} if it sees the name twice. The definition had been
 * copied onto all nine tenant-scoped entities, each declaring it identically —
 * which reads naturally enough that it survived review, and which nothing could
 * catch, because the failure happens while Hibernate builds the
 * {@code EntityManagerFactory} and this application had never successfully
 * started.
 *
 * <p>The split is: <b>{@code @FilterDef} declares the filter once, here;
 * {@code @Filter} applies it, on each entity.</b> Adding a tenant-scoped entity
 * means adding {@code @Filter(name = "collegeFilter", condition = "college_id =
 * :collegeId")} to it and nothing else. Do not copy the {@code @FilterDef} back
 * onto an entity — the application will not start.
 *
 * <p>{@code TenantFilter} enables it per request by name, so what matters is
 * only that exactly one definition of {@code collegeFilter} exists somewhere on
 * the classpath.
 */
@FilterDef(name = "collegeFilter", parameters = @ParamDef(name = "collegeId", type = Long.class))
package com.skillbridge;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
