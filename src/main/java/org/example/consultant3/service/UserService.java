package org.example.consultant3.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.consultant3.entity.User;
import org.example.consultant3.mapper.UserMapper;
import org.example.consultant3.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Map<String, Object> register(String username, String password, String nickname) {
        if (username == null || username.trim().length() < 2 || username.trim().length() > 50) {
            return error("用户名长度需在2-50个字符之间");
        }
        if (password == null || password.length() < 6) {
            return error("密码长度不能少于6位");
        }

        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username.trim());
        if (userMapper.selectOne(wrapper) != null) {
            return error("用户名已存在");
        }

        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null && !nickname.trim().isEmpty() ? nickname.trim() : username.trim());
        userMapper.insert(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        return result;
    }

    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) {
            return error("用户名和密码不能为空");
        }

        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username.trim());
        User user = userMapper.selectOne(wrapper);

        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return error("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        return result;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}
