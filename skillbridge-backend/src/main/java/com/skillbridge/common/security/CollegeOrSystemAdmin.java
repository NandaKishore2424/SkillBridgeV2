package com.skillbridge.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Administrative work a college does for itself and the platform owner may do for any college.
 *
 * <p>Carries {@literal @}PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')"). Naming the rule keeps it
 * greppable, spelled one way, and impossible to mistype into an expression that
 * silently allows everyone. {@code EndpointRolesTest} lists every endpoint with
 * the roles it admits; {@code RoleAnnotationRulesTest} keeps the expression
 * itself out of the controllers.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')")
public @interface CollegeOrSystemAdmin {
}
