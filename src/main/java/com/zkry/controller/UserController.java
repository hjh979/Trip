package com.zkry.controller;

import com.zkry.domain.dto.user.CreateUserRequest;
import com.zkry.domain.dto.user.UpdateUserRequest;
import com.zkry.domain.vo.SystemUserView;
import com.zkry.service.SystemUserService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final SystemUserService userService;

    public UserController(SystemUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<SystemUserView> list(@RequestParam(required = false) String keyword) {
        return userService.list(keyword);
    }

    @GetMapping("/{id}")
    public SystemUserView get(@PathVariable Long id) {
        return userService.get(id);
    }

    @PostMapping
    public SystemUserView create(@RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}")
    public SystemUserView update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        userService.delete(id);
        return Map.of("success", true);
    }
}
