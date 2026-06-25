package io.github.memory.benchmark;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface LlmAnswerGenerator {

    @UserMessage("""
            You are a question-answering assistant. You will be given retrieved memory fragments
            from past conversations and a question. Answer the question using ONLY the provided
            memories. If the memories do not contain enough information, say "I don't know" or
            "Not mentioned in the provided context."
            Be concise and factual. Do not make up information.

            Retrieved memories:
            {memories}

            Question: {question}

            Answer:""")
    String generateAnswer(@V("memories") String memories, @V("question") String question);
}
