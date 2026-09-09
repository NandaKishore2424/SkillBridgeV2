package com.skillbridge.auth.security;

import com.skillbridge.auth.entity.User;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The security principal.
 *
 * <p>Previously the raw {@link User} JPA entity was placed directly into the
 * security context. It implements neither {@code UserDetails} nor
 * {@code Principal}, which had two consequences:
 *
 * <ul>
 *   <li>{@code authentication.getName()} fell back to {@code Object.toString()}.
 *       On a Lombok {@code @Data} entity that renders every field — including
 *       {@code passwordHash}. {@code FeedbackController} passed that string to a
 *       service expecting an email, so every feedback call failed <em>and</em>
 *       wrote a bcrypt hash into the logs on the way.</li>
 *   <li>Holding a managed entity in a context that outlives the persistence
 *       context invites lazy-loading failures far from where they originate.</li>
 * </ul>
 *
 * <p>This class is a detached, immutable snapshot taken at authentication time.
 * It carries only the fields authorisation actually needs, so nothing here can
 * trigger a lazy load and there is no hash to leak — {@link #getPassword()}
 * returns {@code null}, since token authentication never re-checks a password.
 */
@Getter
@EqualsAndHashCode(of = "id")
public class AuthenticatedUser implements UserDetails {

    private final Long id;
    private final String email;

    /** Null for SYSTEM_ADMIN, who is not scoped to any college. */
    private final Long collegeId;

    private final boolean active;
    private final boolean mustChangePassword;
    private final Set<String> roles;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedUser(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.collegeId = user.getCollegeId();
        this.active = Boolean.TRUE.equals(user.getIsActive());
        this.mustChangePassword = Boolean.TRUE.equals(user.getMustChangePassword());
        this.roles = user.getRoles().stream()
                .map(role -> role.getName())
                .collect(Collectors.toUnmodifiableSet());
        this.authorities = this.roles.stream()
                .map(name -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + name))
                .toList();
    }

    /**
     * Builds the principal from a verified token's claims, without touching the
     * database.
     *
     * <p>The signature is what makes this safe: a claim cannot be altered
     * without the signing key, so these values are exactly what the server put
     * there when the token was issued. What they are not is *fresh* — they
     * describe the user as of issue time, so a role change or a deactivation
     * takes effect when the token expires rather than on the next request. That
     * is the trade this constructor exists to make, and the access-token TTL is
     * its bound.
     *
     * <p>{@code roles} is required and must be non-empty. A token with no roles
     * would otherwise produce a principal with no authorities that still passes
     * authentication, which reads as "logged in but everything is forbidden" —
     * confusing to debug and one refactor away from being defaulted to
     * something worse.
     */
    public AuthenticatedUser(Long id, String email, Long collegeId, boolean active,
                             boolean mustChangePassword, Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot build an authenticated principal with no roles (user " + id + ")");
        }
        this.id = id;
        this.email = email;
        this.collegeId = collegeId;
        this.active = active;
        this.mustChangePassword = mustChangePassword;
        this.roles = Set.copyOf(roles);
        this.authorities = this.roles.stream()
                .map(name -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + name))
                .toList();
    }

    /** True for a system administrator, who deliberately has no tenant scope. */
    public boolean isSystemAdmin() {
        return roles.contains("SYSTEM_ADMIN");
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    // --- UserDetails -------------------------------------------------------

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * Always null. The credential was verified when the token was issued; there
     * is no reason to carry a password hash around afterwards, and every reason
     * not to.
     */
    @Override
    public String getPassword() {
        return null;
    }

    /**
     * The email. This is what {@code authentication.getName()} returns, which is
     * what the rest of the application has always assumed it returned.
     */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    /** Never render the whole object into a log line again. */
    @Override
    public String toString() {
        return "AuthenticatedUser(id=" + id + ", email=" + email + ", collegeId=" + collegeId + ")";
    }
}
