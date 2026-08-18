package com.distributedjudge.service;

import com.distributedjudge.config.JudgeProperties;
import com.distributedjudge.dto.AuthRequest;
import com.distributedjudge.dto.AuthResponse;
import com.distributedjudge.dto.UserResponse;
import com.distributedjudge.model.AuthMode;
import com.distributedjudge.model.UserAccount;
import com.distributedjudge.repository.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!worker")
public class AuthService {
    private static final String SESSION_KEY_PREFIX = "session:";

    private final UserAccountRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final StringRedisTemplate redisTemplate;
    private final Duration sessionTtl;

    public AuthService(
            UserAccountRepository userRepository,
            PasswordHasher passwordHasher,
            StringRedisTemplate redisTemplate,
            JudgeProperties properties
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.redisTemplate = redisTemplate;
        this.sessionTtl = Duration.ofHours(properties.getSessionTtlHours());
    }

    @Transactional
    public AuthResponse loginWithPassword(AuthRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw new InvalidCredentialsException("Password is required");
        }
        String email = normalizeEmail(request.email());
        UserAccount user = userRepository.findByEmail(email).orElse(null);
        boolean newUser = user == null;

        if (newUser) {
            user = createUser(email, request.displayName());
            user.setPasswordHash(passwordHasher.hash(request.password()));
        } else if (user.getPasswordHash() == null) {
            user.setPasswordHash(passwordHasher.hash(request.password()));
        } else if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        user.setAuthMode(AuthMode.PASSWORD);
        user.setLastLoginAt(Instant.now());
        user = userRepository.save(user);
        return response(user, newUser);
    }

    @Transactional
    public AuthResponse loginWithEmail(AuthRequest request) {
        String email = normalizeEmail(request.email());
        UserAccount user = userRepository.findByEmail(email).orElse(null);
        boolean newUser = user == null;

        if (newUser) {
            user = createUser(email, request.displayName());
        } else if (user.getPasswordHash() != null) {
            throw new InvalidCredentialsException("This account requires a password to sign in");
        }

        user.setAuthMode(AuthMode.EMAIL_ONLY);
        user.setLastLoginAt(Instant.now());
        user = userRepository.save(user);
        return response(user, newUser);
    }

    @Transactional(readOnly = true)
    public UserResponse me(String authorizationHeader) {
        return toUserResponse(requireUser(authorizationHeader));
    }

    @Transactional(readOnly = true)
    public String requireEmail(String authorizationHeader) {
        return requireUser(authorizationHeader).getEmail();
    }

    @Transactional(readOnly = true)
    public Optional<UserResponse> authenticateToken(String token) {
        String email = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + token);
        if (email == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email).map(this::toUserResponse);
    }

    private UserAccount requireUser(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        String email = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + token);
        if (email == null) {
            throw new UnauthenticatedException("Please sign in before submitting code");
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthenticatedException("Signed-in user no longer exists"));
    }

    private AuthResponse response(UserAccount user, boolean newUser) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + token, user.getEmail(), sessionTtl);
        return new AuthResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAuthMode(),
                token,
                newUser
        );
    }

    private UserAccount createUser(String email, String displayName) {
        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setDisplayName(displayName == null || displayName.isBlank() ? email.substring(0, email.indexOf('@')) : displayName.strip());
        user.setAuthMode(AuthMode.EMAIL_ONLY);
        user.setCreatedAt(Instant.now());
        user.setLastLoginAt(Instant.now());
        return user;
    }

    private UserResponse toUserResponse(UserAccount user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAuthMode(),
                user.getLastLoginAt()
        );
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new UnauthenticatedException("Missing Authorization header");
        }
        if (authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7).strip();
        }
        return authorizationHeader.strip();
    }
}
