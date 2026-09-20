package com.skillbridge.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A student and the trainer who teaches them, for the same view of progress.
 *
 * <p>Carries {@literal @}PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')"). Naming the rule keeps it
 * greppable, spelled one way, and impossible to mistype into an expression that
 * silently allows everyone. {@code EndpointRolesTest} lists every endpoint with
 * the roles it admits; {@code RoleAnnotationRulesTest} keeps the expression
 * itself out of the controllers.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')")
public @interface StudentOrTrainer {
}
