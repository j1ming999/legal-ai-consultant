package org.example.consultant3.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.consultant3.entity.Conversation;
import org.example.consultant3.entity.Message;
import org.example.consultant3.mapper.ConversationMapper;
import org.example.consultant3.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @GetMapping("/list")
    public Map<String, Object> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        QueryWrapper<Conversation> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).orderByDesc("updated_at");
        List<Conversation> conversations = conversationMapper.selectList(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("conversations", conversations);
        return result;
    }

    @PostMapping("/create")
    public Map<String, Object> create(HttpServletRequest request, @RequestBody(required = false) Map<String, String> body) {
        Long userId = (Long) request.getAttribute("userId");

        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(body != null && body.get("title") != null ? body.get("title") : "新对话");
        conversationMapper.insert(conv);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("conversation", conv);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");

        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !conv.getUserId().equals(userId)) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "对话不存在");
            return result;
        }

        QueryWrapper<Message> msgWrapper = new QueryWrapper<>();
        msgWrapper.eq("conversation_id", id);
        messageMapper.delete(msgWrapper);

        conversationMapper.deleteById(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");

        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !conv.getUserId().equals(userId)) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "对话不存在");
            return result;
        }

        QueryWrapper<Message> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", id).orderByAsc("created_at");
        List<Message> messages = messageMapper.selectList(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("messages", messages);
        return result;
    }
}
