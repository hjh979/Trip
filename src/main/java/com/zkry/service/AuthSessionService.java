package com.zkry.service;

import com.zkry.domain.entity.SystemUser;
import com.zkry.domain.entity.AuthSession;
import com.zkry.mapper.SystemUserMapper;
import com.zkry.mapper.AuthSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.security.VoyagePrincipal;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthSessionMapper sessionMapper;
    private final SystemUserMapper userMapper;
    private final long sessionHours;

    public AuthSessionService(
        AuthSessionMapper sessionMapper,
        SystemUserMapper userMapper,
        @Value("${voyagemind.security.session-hours:12}") long sessionHours
    ) {
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.sessionHours = Math.max(1, Math.min(sessionHours, 24 * 30));
    }

    public SessionToken create(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration ttl = Duration.ofHours(sessionHours);
        AuthSession session = new AuthSession();
        session.setTokenHash(key(token));
        session.setUserId(userId);
        session.setExpiresAt(java.time.LocalDateTime.now().plus(ttl));
        sessionMapper.insert(session);
        return new SessionToken(token, ttl.toSeconds());
    }

    public Optional<VoyagePrincipal> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        AuthSession session = sessionMapper.selectOne(Wrappers.<AuthSession>lambdaQuery()
            .eq(AuthSession::getTokenHash, key(token)).eq(AuthSession::getDeleted, 0).last("LIMIT 1"));
        if (session == null || session.getExpiresAt() == null || session.getExpiresAt().isBefore(java.time.LocalDateTime.now())) return Optional.empty();
        try {
            SystemUser user = userMapper.selectById(session.getUserId());
            if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) return Optional.empty();
            return Optional.of(new VoyagePrincipal(user.getId(), user.getUsername(), user.getRole()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public void invalidate(String token) {
        if (token != null && !token.isBlank()) sessionMapper.delete(Wrappers.<AuthSession>lambdaUpdate()
            .eq(AuthSession::getTokenHash, key(token)));
    }

    /** Store only a one-way digest so a Redis dump cannot be used as a bearer-token list. */
    private String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record SessionToken(String value, long expiresInSeconds) {
    }
}
