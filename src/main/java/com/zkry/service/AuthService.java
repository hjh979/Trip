package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.auth.LoginRequest;
import com.zkry.domain.dto.auth.RegisterRequest;
import com.zkry.domain.dto.user.CreateUserRequest;
import com.zkry.domain.dto.user.UpdateUserRequest;
import com.zkry.domain.entity.SystemUser;
import com.zkry.domain.entity.UserCredential;
import com.zkry.domain.vo.AuthSessionView;
import com.zkry.domain.vo.SystemUserView;
import com.zkry.mapper.SystemUserMapper;
import com.zkry.mapper.UserCredentialMapper;
import java.time.LocalDateTime;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final SystemUserMapper userMapper;
    private final UserCredentialMapper credentialMapper;
    private final SystemUserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessionService;

    public AuthService(
        SystemUserMapper userMapper,
        UserCredentialMapper credentialMapper,
        SystemUserService userService,
        PasswordEncoder passwordEncoder,
        AuthSessionService sessionService
    ) {
        this.userMapper = userMapper;
        this.credentialMapper = credentialMapper;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
    }

    public AuthSessionView login(LoginRequest request) {
        if (request == null || blank(request.account()) || blank(request.password())) {
            throw new BizException("请输入账号和密码。");
        }
        String account = request.account().trim();
        SystemUser user = userMapper.selectOne(
            Wrappers.<SystemUser>lambdaQuery()
                .and(query -> query.eq(SystemUser::getUsername, account).or().eq(SystemUser::getEmail, account))
                .last("LIMIT 1")
        );
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BizException("账号或密码错误。");
        }
        UserCredential credential = credentialMapper.selectOne(
            Wrappers.<UserCredential>lambdaQuery().eq(UserCredential::getUserId, user.getId()).last("LIMIT 1")
        );
        if (credential == null || !passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            throw new BizException("账号或密码错误。");
        }
        return session(user);
    }

    @Transactional
    public AuthSessionView register(RegisterRequest request) {
        if (request == null) throw new BizException("注册信息不能为空。");
        validatePassword(request.password());
        SystemUserView created = userService.create(new CreateUserRequest(
            request.username(), request.display_name(), request.email(), "", "USER", ""
        ));
        UserCredential credential = new UserCredential();
        credential.setUserId(created.id());
        credential.setPasswordHash(passwordEncoder.encode(request.password()));
        credential.setPasswordUpdatedAt(LocalDateTime.now());
        credentialMapper.insert(credential);
        return session(userMapper.selectById(created.id()));
    }

    public SystemUserView current(Long userId) {
        return userService.get(userId);
    }

    public SystemUserView updateProfile(Long userId, UpdateUserRequest request) {
        if (request == null) throw new BizException("个人资料不能为空。");
        return userService.update(userId, new UpdateUserRequest(
            request.display_name(), request.email(), request.avatar_url(), null, null, request.bio()
        ));
    }

    public void logout(String token) {
        sessionService.invalidate(token);
    }

    private AuthSessionView session(SystemUser user) {
        AuthSessionService.SessionToken token = sessionService.create(user.getId());
        return new AuthSessionView(token.value(), "Bearer", token.expiresInSeconds(), userService.toView(user));
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new BizException("密码长度需要为 8-72 位。");
        }
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BizException("密码至少需要包含一个字母和一个数字。");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
