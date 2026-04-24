# Authentication & Security

This document explains how SkillBridge handles user authentication, authorization, and security.

---

## Authentication Overview

**Authentication** = "Who are you?" (verifying identity)  
**Authorization** = "What can you do?" (verifying permissions)

### Authentication Flow

```
1. User provides credentials (email + password)
2. Server validates credentials
3. Server generates JWT tokens
4. Client stores tokens securely
5. Client sends token with every request
6. Server validates token and extracts user info
```

---

## JWT (JSON Web Token) Authentication

### What is JWT?

**JWT** is a compact, URL-safe token format for securely transmitting information between parties.

### JWT Structure

A JWT has three parts separated by dots (`.`):

```
header.payload.signature
```

**Example:**
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

### JWT Parts Explained

**1. Header:**
```json
{
  "alg": "HS256",  // Algorithm (HMAC SHA256)
  "typ": "JWT"     // Type
}
```
Base64Url encoded → `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9`

**2. Payload (Claims):**
```json
{
  "sub": "1234567890",           // Subject (user ID)
  "email": "student@college.edu",
  "role": "STUDENT",
  "collegeId": 1,                // Tenant identifier
  "iat": 1516239022,            // Issued at (timestamp)
  "exp": 1516242622             // Expiration (timestamp)
}
```
Base64Url encoded → `eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ`

**3. Signature:**
```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secret
)
```
→ `SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c`

**Why Signature?**
- Prevents tampering: If payload is changed, signature won't match
- Verifies authenticity: Only server with secret can create valid tokens

---

## Authentication Implementation

### Step 1: User Registration/Login

**Registration Endpoint:**
```java
@PostMapping("/api/v1/auth/register")
public ResponseEntity<AuthResponse> register(@RequestBody @Valid RegisterRequest request) {
    // 1. Validate email doesn't exist
    if (userRepository.existsByEmail(request.getEmail())) {
        throw new EmailAlreadyExistsException(request.getEmail());
    }
    
    // 2. Hash password
    String hashedPassword = passwordEncoder.encode(request.getPassword());
    
    // 3. Create user
    User user = new User();
    user.setEmail(request.getEmail());
    user.setPassword(hashedPassword);
    user.setRole(Role.STUDENT);
    user.setCollegeId(request.getCollegeId());
    user = userRepository.save(user);
    
    // 4. Generate tokens
    String accessToken = jwtTokenProvider.generateAccessToken(user);
    String refreshToken = jwtTokenProvider.generateRefreshToken(user);
    
    // 5. Save refresh token
    refreshTokenService.saveRefreshToken(user.getId(), refreshToken);
    
    // 6. Return tokens
    return ResponseEntity.ok(new AuthResponse(accessToken, refreshToken));
}
```

**Login Endpoint:**
```java
@PostMapping("/api/v1/auth/login")
public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest request) {
    // 1. Find user by email
    User user = userRepository.findByEmail(request.getEmail())
        .orElseThrow(() -> new InvalidCredentialsException());
    
    // 2. Verify password
    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
        throw new InvalidCredentialsException();
    }
    
    // 3. Check if account is active
    if (!user.isActive()) {
        throw new AccountDisabledException();
    }
    
    // 4. Generate tokens
    String accessToken = jwtTokenProvider.generateAccessToken(user);
    String refreshToken = jwtTokenProvider.generateRefreshToken(user);
    
    // 5. Save refresh token
    refreshTokenService.saveRefreshToken(user.getId(), refreshToken);
    
    // 6. Return tokens
    return ResponseEntity.ok(new AuthResponse(accessToken, refreshToken));
}
```

### Step 2: JWT Token Provider

```java
@Component
public class JwtTokenProvider {
    
    @Value("${jwt.secret}")
    private String secret;
    
    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;
    
    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;
    
    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpirationMs);
        
        return Jwts.builder()
            .setSubject(user.getId().toString())
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .claim("collegeId", user.getCollegeId())
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(SignatureAlgorithm.HS512, secret)
            .compact();
    }
    
    public String generateRefreshToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpirationMs);
        
        return Jwts.builder()
            .setSubject(user.getId().toString())
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(SignatureAlgorithm.HS512, secret)
            .compact();
    }
    
    public Claims getClaimsFromToken(String token) {
        return Jwts.parser()
            .setSigningKey(secret)
            .parseClaimsJws(token)
            .getBody();
    }
    
    public boolean validateToken(String token) {
        try {
            Jwts.parser().setSigningKey(secret).parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
```

### Step 3: Password Hashing

**Why Hash Passwords?**
- If database is compromised, attackers can't see actual passwords
- One-way function: Can't reverse hash to get password

**Using BCrypt (Spring Security):**
```java
@Configuration
public class SecurityConfig {
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // Strength: 12 rounds
    }
}
```

**How BCrypt Works:**
```
Password: "MyPassword123!"
Hash: "$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyY5Y5Y5Y5Y5"
```

**Verification:**
```java
// Store: passwordEncoder.encode("MyPassword123!")
// Verify: passwordEncoder.matches("MyPassword123!", storedHash)
```

---

## Token Storage & Security

### Access Token Storage

**Options:**
1. **Memory (React state)** - ✅ Recommended
2. **httpOnly Cookie** - More secure, but harder to access in JS
3. **localStorage** - ❌ Vulnerable to XSS attacks

**Recommended: Memory + httpOnly Cookie Hybrid:**
```typescript
// Frontend: Store in memory (React state)
const [accessToken, setAccessToken] = useState<string | null>(null);

// Backend: Also set httpOnly cookie (for additional security)
response.setCookie("accessToken", token, {
  httpOnly: true,
  secure: true,  // HTTPS only
  sameSite: "strict"
});
```

### Refresh Token Storage

**Decision:** ✅ Store in database

**Why:**
- Can revoke tokens immediately (if account compromised)
- Auditable (track token usage)
- Survives server restarts
- Can implement token rotation

**Schema:**
```sql
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(255) NOT NULL,  -- Hashed token (not plain text)
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
```

**Implementation:**
```java
@Service
public class RefreshTokenService {
    
    public void saveRefreshToken(Long userId, String token) {
        // Hash token before storing
        String tokenHash = hashToken(token);
        
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(userId);
        refreshToken.setTokenHash(tokenHash);
        refreshToken.setExpiresAt(calculateExpiryDate());
        refreshTokenRepository.save(refreshToken);
    }
    
    public boolean validateRefreshToken(String token) {
        String tokenHash = hashToken(token);
        Optional<RefreshToken> stored = refreshTokenRepository.findByTokenHash(tokenHash);
        
        if (stored.isEmpty()) {
            return false; // Token not found
        }
        
        RefreshToken rt = stored.get();
        if (rt.isRevoked()) {
            return false; // Token revoked
        }
        
        if (rt.getExpiresAt().before(new Date())) {
            return false; // Token expired
        }
        
        return true;
    }
    
    public void revokeRefreshToken(String token) {
        String tokenHash = hashToken(token);
        refreshTokenRepository.findByTokenHash(tokenHash)
            .ifPresent(rt -> {
                rt.setRevoked(true);
                refreshTokenRepository.save(rt);
            });
    }
}
```

---

## Token Refresh Flow

### Why Refresh Tokens?

**Problem with Long-Lived Access Tokens:**
- If token is stolen, attacker has access until expiration
- Can't revoke access immediately

**Solution: Short-Lived Access Tokens + Long-Lived Refresh Tokens**
- Access token: 15 minutes (short-lived, can't do much damage if stolen)
- Refresh token: 7 days (long-lived, stored securely, can be revoked)

### Refresh Flow

```
1. Access token expires (after 15 min)
2. Client sends refresh token to server
3. Server validates refresh token
4. Server generates new access token
5. Optionally: Rotate refresh token (generate new one, revoke old one)
6. Client receives new access token
```

**Implementation:**
```java
@PostMapping("/api/v1/auth/refresh")
public ResponseEntity<AuthResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
    // 1. Validate refresh token
    if (!refreshTokenService.validateRefreshToken(request.getRefreshToken())) {
        throw new InvalidRefreshTokenException();
    }
    
    // 2. Extract user ID from refresh token
    Claims claims = jwtTokenProvider.getClaimsFromToken(request.getRefreshToken());
    Long userId = Long.parseLong(claims.getSubject());
    
    // 3. Load user
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new UserNotFoundException(userId));
    
    // 4. Generate new access token
    String newAccessToken = jwtTokenProvider.generateAccessToken(user);
    
    // 5. Optionally rotate refresh token
    String newRefreshToken = jwtTokenProvider.generateRefreshToken(user);
    refreshTokenService.revokeRefreshToken(request.getRefreshToken());
    refreshTokenService.saveRefreshToken(userId, newRefreshToken);
    
    // 6. Return new tokens
    return ResponseEntity.ok(new AuthResponse(newAccessToken, newRefreshToken));
}
```

---

## Authorization: Role-Based Access Control (RBAC)

### Roles in SkillBridge

| Role | Description | Scope |
|------|-------------|-------|
| **SYSTEM_ADMIN** | Platform administrator | All colleges |
| **COLLEGE_ADMIN** | College administrator | One college |
| **TRAINER** | Training instructor | Assigned batches |
| **STUDENT** | Training participant | Own data |

### Spring Security Configuration

```java
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {
    
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()  // JWT is stateless, CSRF not needed
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/api/v1/auth/**").permitAll()
                .antMatchers("/api/v1/public/**").permitAll()
                .anyRequest().authenticated()
            .and()
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}
```

### JWT Authentication Filter

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Autowired
    private JwtTokenProvider tokenProvider;
    
    @Autowired
    private UserDetailsService userDetailsService;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. Extract token from Authorization header
            String token = getTokenFromRequest(request);
            
            if (token != null && tokenProvider.validateToken(token)) {
                // 2. Extract user info from token
                Claims claims = tokenProvider.getClaimsFromToken(token);
                String email = claims.get("email", String.class);
                
                // 3. Load user details
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                
                // 4. Create authentication object
                UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(
                        userDetails, 
                        null, 
                        userDetails.getAuthorities()
                    );
                
                // 5. Set in security context
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication", e);
        }
        
        filterChain.doFilter(request, response);
    }
    
    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

### Method-Level Security

```java
@Service
public class BatchService {
    
    // Only COLLEGE_ADMIN can create batches
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public Batch createBatch(BatchDTO dto) {
        // Implementation
    }
    
    // TRAINER can only update batches they're assigned to
    @PreAuthorize("hasRole('TRAINER') and @batchService.isAssignedToBatch(authentication.name, #batchId)")
    public void updateBatch(Long batchId, BatchDTO dto) {
        // Implementation
    }
    
    // STUDENT can only view their own batches
    @PreAuthorize("hasRole('STUDENT') and @batchService.isEnrolledInBatch(authentication.name, #batchId)")
    public Batch getBatch(Long batchId) {
        // Implementation
    }
}
```

---

## Password Security

### Password Rules

**Decision:** ✅ Enforce enterprise password rules

**Rules:**
- Minimum 8 characters
- At least 1 uppercase letter
- At least 1 lowercase letter
- At least 1 number
- At least 1 special character

**Implementation:**
```java
@Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
         message = "Password must be at least 8 characters with uppercase, lowercase, number, and special character")
private String password;
```

### Password Reset Flow

```
1. User requests password reset
2. Server generates reset token (random, time-bound)
3. Server sends email with reset link
4. User clicks link, enters new password
5. Server validates token and updates password
6. Server invalidates token (one-time use)
```

**Implementation:**
```java
@PostMapping("/api/v1/auth/forgot-password")
public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest request) {
    User user = userRepository.findByEmail(request.getEmail())
        .orElseThrow(() -> new UserNotFoundException());
    
    // Generate reset token
    String resetToken = UUID.randomUUID().toString();
    user.setPasswordResetToken(resetToken);
    user.setPasswordResetTokenExpiry(LocalDateTime.now().plusMinutes(30));
    userRepository.save(user);
    
    // Send email
    emailService.sendPasswordResetEmail(user.getEmail(), resetToken);
    
    return ResponseEntity.ok().build();
}

@PostMapping("/api/v1/auth/reset-password")
public ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
    User user = userRepository.findByPasswordResetToken(request.getToken())
        .orElseThrow(() -> new InvalidResetTokenException());
    
    // Check token expiry
    if (user.getPasswordResetTokenExpiry().isBefore(LocalDateTime.now())) {
        throw new ExpiredResetTokenException();
    }
    
    // Update password
    user.setPassword(passwordEncoder.encode(request.getNewPassword()));
    user.setPasswordResetToken(null);
    user.setPasswordResetTokenExpiry(null);
    userRepository.save(user);
    
    // Revoke all refresh tokens (security best practice)
    refreshTokenService.revokeAllUserTokens(user.getId());
    
    return ResponseEntity.ok().build();
}
```

---

## Security Best Practices

### 1. Never Log Sensitive Data

```java
// ❌ BAD
logger.info("User logged in: {}", user.getPassword());

// ✅ GOOD
logger.info("User logged in: {}", user.getEmail());
```

### 2. Use HTTPS in Production

```yaml
# application-prod.yml
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
```

### 3. Rate Limiting (Prevent Brute Force)

```java
@RateLimiter(name = "login", fallbackMethod = "loginFallback")
@PostMapping("/api/v1/auth/login")
public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
    // Implementation
}
```

### 4. Input Validation

```java
@PostMapping("/api/v1/auth/register")
public ResponseEntity<AuthResponse> register(@RequestBody @Valid RegisterRequest request) {
    // @Valid ensures all @NotNull, @Email, @Size annotations are checked
}
```

### 5. SQL Injection Prevention

**Spring Data JPA automatically prevents SQL injection:**
```java
// ✅ SAFE - JPA handles parameterization
@Query("SELECT u FROM User u WHERE u.email = :email")
User findByEmail(@Param("email") String email);

// ❌ DANGEROUS - Never do this
@Query("SELECT u FROM User u WHERE u.email = '" + email + "'")
User findByEmail(String email);
```

---

## Summary

**Authentication Flow:**
1. User logs in → Server validates credentials
2. Server generates JWT tokens (access + refresh)
3. Client stores tokens securely
4. Client sends access token with each request
5. Server validates token and extracts user info
6. When access token expires, client uses refresh token to get new one

**Security Measures:**
- ✅ Password hashing (BCrypt)
- ✅ JWT tokens (stateless, scalable)
- ✅ Refresh token rotation
- ✅ Role-based access control
- ✅ Method-level security
- ✅ Input validation
- ✅ HTTPS in production
- ✅ Rate limiting

**Key Principles:**
- Never trust client input
- Always validate and sanitize
- Use strong password hashing
- Keep access tokens short-lived
- Store refresh tokens securely
- Implement proper authorization checks

