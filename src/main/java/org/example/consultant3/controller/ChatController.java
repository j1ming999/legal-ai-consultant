package org.example.consultant3.controller;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.example.consultant3.aiservice.ConsultantService;
import org.example.consultant3.entity.Conversation;
import org.example.consultant3.entity.Message;
import org.example.consultant3.mapper.ConversationMapper;
import org.example.consultant3.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
public class ChatController {
    @Autowired
    private ConsultantService consultantService;

    @Autowired
    private ContentRetriever contentRetriever;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String memoryId, String message,
                             @RequestParam(required = false) Long conversationId,
                             HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        if (userId != null && conversationId != null) {
            Conversation conv = conversationMapper.selectById(conversationId);
            if (conv != null && conv.getUserId().equals(userId)) {
                Message userMsg = new Message();
                userMsg.setConversationId(conversationId);
                userMsg.setRole("user");
                userMsg.setContent(message);
                messageMapper.insert(userMsg);

                if ("新对话".equals(conv.getTitle())) {
                    String title = message.length() > 20 ? message.substring(0, 20) + "..." : message;
                    UpdateWrapper<Conversation> update = new UpdateWrapper<>();
                    update.eq("id", conversationId).set("title", title);
                    conversationMapper.update(null, update);
                }
            }
        }

        String effectiveMemoryId = (userId != null && conversationId != null)
                ? "user-" + userId + "-conv-" + conversationId
                : (memoryId != null ? memoryId : UUID.randomUUID().toString());

        StringBuilder responseBuffer = new StringBuilder();
        final Long finalConvId = conversationId;
        final Long finalUserId = userId;

        return consultantService.chat(effectiveMemoryId, message)
                .doOnNext(responseBuffer::append)
                .doOnComplete(() -> {
                    if (finalUserId != null && finalConvId != null && responseBuffer.length() > 0) {
                        Message assistantMsg = new Message();
                        assistantMsg.setConversationId(finalConvId);
                        assistantMsg.setRole("assistant");
                        assistantMsg.setContent(responseBuffer.toString());
                        messageMapper.insert(assistantMsg);
                    }
                });
    }

    @GetMapping(value = "/chat/sources", produces = "application/json;charset=utf-8")
    public List<Map<String, String>> getSources(@RequestParam String message) {
        List<Content> results = contentRetriever.retrieve(Query.from(message));
        List<Map<String, String>> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Content c : results) {
            String lawName = c.textSegment().metadata().getString("law_name");
            String chapter = c.textSegment().metadata().getString("chapter");
            String article = c.textSegment().metadata().getString("article_number");
            String key = (lawName != null ? lawName : "") + ":" + (article != null ? article : "");
            if (seen.contains(key)) continue;
            seen.add(key);

            Map<String, String> source = new LinkedHashMap<>();
            source.put("law_name", lawName != null ? lawName : "");
            source.put("chapter", chapter != null ? chapter : "");
            source.put("article_number", article != null ? article : "");
            source.put("text_preview", c.textSegment().text().substring(0,
                    Math.min(100, c.textSegment().text().length())) + "...");
            sources.add(source);
        }
        return sources;
    }
}
