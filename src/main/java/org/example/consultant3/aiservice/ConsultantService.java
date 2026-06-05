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
//        chatMemory = "chatMemory",
        chatMemoryProvider = "chatMemoryProvider",
        contentRetriever = "contentRetriever"
)
//@AiService
public interface ConsultantService {
//    public String chat(String message);
//    @SystemMessage("你是我的助手小月月，人美心善又多金")
        @SystemMessage(fromResource = "system.txt")
//        @UserMessage("你是我的助手小月月，人美心善又多金{{it}}")
        public Flux<String> chat(@MemoryId String memoryId,@UserMessage String message);
}
