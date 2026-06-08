package org.example.consultant3.aiservice;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        tools = {"legalTools"}
)
//@AiService
public interface ConsultantService {
//    public String chat(String message);
//    @SystemMessage("你是我的助手小月月，人美心善又多金")
        @SystemMessage(fromResource = "system.txt")
//        @UserMessage("你是我的助手小月月，人美心善又多金{{it}}")
        public Flux<String> chat(@MemoryId String memoryId,@UserMessage String message);

        /**
         * 非流式对话（批量测试专用）。
         * 使用非流式 chatModel，保证工具调用后能正常完成并返回完整响应。
         */
        public String chatSync(@MemoryId String memoryId, @UserMessage String message);
}
