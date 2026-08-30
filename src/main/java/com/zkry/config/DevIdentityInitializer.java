package com.zkry.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.domain.entity.SystemUser;
import com.zkry.domain.entity.UserCredential;
import com.zkry.mapper.SystemUserMapper;
import com.zkry.mapper.UserCredentialMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevIdentityInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevIdentityInitializer.class);

    private final SystemUserMapper userMapper;
    private final UserCredentialMapper credentialMapper;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String adminPassword;
    private final String userPassword;

    public DevIdentityInitializer(
        SystemUserMapper userMapper,
        UserCredentialMapper credentialMapper,
        PasswordEncoder passwordEncoder,
        @Value("${voyagemind.security.dev-bootstrap.enabled:true}") boolean enabled,
        @Value("${voyagemind.security.dev-bootstrap.admin-password:VoyageAdmin@2026}") String adminPassword,
        @Value("${voyagemind.security.dev-bootstrap.user-password:VoyageUser@2026}") String userPassword
    ) {
        this.userMapper = userMapper;
        this.credentialMapper = credentialMapper;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.adminPassword = adminPassword;
        this.userPassword = userPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        ensureCredential(1001L, "SUPER_ADMIN", adminPassword);
        for (Long userId : List.of(1002L, 1003L)) ensureCredential(userId, "USER", userPassword);
        log.warn("[DevIdentity] 本地开发账号已初始化；部署前请关闭 DEV_BOOTSTRAP_USERS 并更换密码。");
    }

    private void ensureCredential(Long userId, String role, String password) {
        SystemUser user = userMapper.selectById(userId);
        if (user == null) return;
        if (!role.equals(user.getRole())) {
            user.setRole(role);
            userMapper.updateById(user);
        }
        long count = credentialMapper.selectCount(
            Wrappers.<UserCredential>lambdaQuery().eq(UserCredential::getUserId, userId)
        );
        if (count > 0) return;
        UserCredential credential = new UserCredential();
        credential.setUserId(userId);
        credential.setPasswordHash(passwordEncoder.encode(password));
        credential.setPasswordUpdatedAt(LocalDateTime.now());
        credentialMapper.insert(credential);
    }
}
