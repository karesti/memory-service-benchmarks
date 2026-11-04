package io.github.memory.benchmark;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface LlmBlindAnswerGenerator {

    @UserMessage("""
            You are a question-answering assistant. Answer the following question based solely on your general knowledge.
            You have NO access to any conversation history or context.
            
            Provide a brief, direct answer. If you don't know, say "I don't know."

            Question: {question}

            Answer:""")
    Result<String> generateAnswer(@V("question") String question);
}
