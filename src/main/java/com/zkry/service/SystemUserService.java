package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.user.CreateUserRequest;
import com.zkry.domain.dto.user.UpdateUserRequest;
import com.zkry.domain.entity.SystemUser;
import com.zkry.domain.vo.SystemUserView;
import com.zkry.mapper.SystemUserMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemUserService {

    private static final List<String> ROLES = List.of("SUPER_ADMIN", "ADMIN", "USER");
    private static final List<String> STATUSES = List.of("ACTIVE", "DISABLED");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SystemUserMapper mapper;

    public SystemUserService(SystemUserMapper mapper) {
        this.mapper = mapper;
    }

    public List<SystemUserView> list(String keyword) {
        var query = Wrappers.<SystemUser>lambdaQuery().orderByAsc(SystemUser::getDisplayName);
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            query.and(wrapper -> wrapper
                .like(SystemUser::getUsername, term)
                .or().like(SystemUser::getDisplayName, term)
                .or().like(SystemUser::getEmail, term));
        }
        return mapper.selectList(query).stream().map(this::toView).toList();
    }

    public SystemUser getEntity(Long id) {
        if (id == null) {
            throw new BizException("用户 ID 不能为空。");
        }
        SystemUser user = mapper.selectById(id);
        if (user == null) {
            throw new BizException("用户不存在，id=" + id);
        }
        return user;
    }

    public SystemUserView get(Long id) {
        return toView(getEntity(id));
    }

    @Transactional
    public SystemUserView create(CreateUserRequest request) {
        if (request == null) {
            throw new BizException("用户信息不能为空。");
        }
        String username = required(request.username(), "用户名");
        if (!username.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw new BizException("用户名只能包含字母、数字、点、下划线或短横线，长度 3-64。");
        }
        ensureUsernameAvailable(username, null);
        String email = nullable(request.email());
        ensureEmailAvailable(email, null);

        SystemUser user = new SystemUser();
        user.setUsername(username);
        user.setDisplayName(required(request.display_name(), "展示名称"));
        user.setEmail(email);
        user.setAvatarUrl(optional(request.avatar_url()));
        user.setRole(normalize(request.role(), ROLES, "USER", "用户角色"));
        user.setStatus("ACTIVE");
        user.setBio(optional(request.bio()));
        mapper.insert(user);
        return toView(user);
    }

    @Transactional
    public SystemUserView update(Long id, UpdateUserRequest request) {
        if (request == null) {
            throw new BizException("用户更新信息不能为空。");
        }
        SystemUser user = getEntity(id);
        String nextRole = normalize(request.role(), ROLES, user.getRole(), "用户角色");
        String nextStatus = normalize(request.status(), STATUSES, user.getStatus(), "用户状态");
        if ("ADMIN".equals(user.getRole()) && (!"ADMIN".equals(nextRole) || "DISABLED".equals(nextStatus))) {
            ensureAnotherActiveAdmin(id);
        }

        String email = request.email() == null ? user.getEmail() : nullable(request.email());
        ensureEmailAvailable(email, id);
        user.setDisplayName(request.display_name() == null
            ? user.getDisplayName()
            : required(request.display_name(), "展示名称"));
        user.setEmail(email);
        if (request.avatar_url() != null) user.setAvatarUrl(optional(request.avatar_url()));
        user.setRole(nextRole);
        user.setStatus(nextStatus);
        if (request.bio() != null) user.setBio(optional(request.bio()));
        mapper.updateById(user);
        return toView(user);
    }

    @Transactional
    public void delete(Long id) {
        SystemUser user = getEntity(id);
        if ("ADMIN".equals(user.getRole()) && "ACTIVE".equals(user.getStatus())) {
            ensureAnotherActiveAdmin(id);
        }
        mapper.deleteById(id);
    }

    public SystemUserView toView(SystemUser user) {
        return new SystemUserView(
            user.getId(), safe(user.getUsername()), safe(user.getDisplayName()), safe(user.getEmail()),
            safe(user.getAvatarUrl()), safe(user.getRole()), safe(user.getStatus()), safe(user.getBio()),
            format(user.getCreateTime())
        );
    }

    private void ensureUsernameAvailable(String username, Long ignoredId) {
        var query = Wrappers.<SystemUser>lambdaQuery().eq(SystemUser::getUsername, username);
        if (ignoredId != null) query.ne(SystemUser::getId, ignoredId);
        if (mapper.selectCount(query) > 0) throw new BizException("用户名已存在：" + username);
    }

    private void ensureEmailAvailable(String email, Long ignoredId) {
        if (email == null || email.isBlank()) return;
        var query = Wrappers.<SystemUser>lambdaQuery().eq(SystemUser::getEmail, email);
        if (ignoredId != null) query.ne(SystemUser::getId, ignoredId);
        if (mapper.selectCount(query) > 0) throw new BizException("邮箱已被使用：" + email);
    }

    private void ensureAnotherActiveAdmin(Long ignoredId) {
        long count = mapper.selectCount(Wrappers.<SystemUser>lambdaQuery()
            .eq(SystemUser::getRole, "ADMIN")
            .eq(SystemUser::getStatus, "ACTIVE")
            .ne(SystemUser::getId, ignoredId));
        if (count == 0) throw new BizException("系统至少需要保留一个启用状态的管理员。");
    }

    private String normalize(String value, List<String> allowed, String fallback, String label) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BizException(label + "无效：" + value);
        return normalized;
    }

    private String required(String value, String label) {
        String text = optional(value);
        if (text.isBlank()) throw new BizException(label + "不能为空。");
        return text;
    }

    private String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullable(String value) {
        String text = optional(value);
        return text.isBlank() ? null : text;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : TIME.format(value);
    }
}
