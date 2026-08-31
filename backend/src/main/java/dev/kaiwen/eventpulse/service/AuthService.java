package dev.kaiwen.eventpulse.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.dto.AuthDtos.LoginRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.LoginVo;
import dev.kaiwen.eventpulse.dto.AuthDtos.RegisterRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.UserVo;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public LoginVo register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new BusinessException("邮箱已被注册");
        }
        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setRole("USER");
        users.save(user);
        return toLoginVo(user);
    }

    public LoginVo login(LoginRequest request) {
        User user = users.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException("邮箱或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException("邮箱或密码错误");
        }
        return toLoginVo(user);
    }

    public UserVo me(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
        return toUserVo(user);
    }

    private LoginVo toLoginVo(User user) {
        return new LoginVo(jwtService.createToken(user.getId(), user.getRole()), toUserVo(user));
    }

    private static UserVo toUserVo(User user) {
        return new UserVo(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
