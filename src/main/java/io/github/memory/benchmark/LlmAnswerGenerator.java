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
            from past conversations and a question. Answer the question based on the provided memories.

            You may reason and draw logical conclusions from the memories. For example:
            - If someone collects classic children's books, you can infer they would have Dr. Seuss books.
            - If someone likes the outdoors and nature, you can infer they would prefer a national park over a theme park.
            - If someone supports LGBTQ rights, you can infer their likely political leaning.
            - If someone had a bad experience on a road trip, you can infer they might not want another one soon.

            Always provide your best answer based on available evidence. Only say "Not mentioned"
            if the memories contain absolutely no relevant information.
            Be concise. Provide a short answer, not a long explanation.

            Retrieved memories:
            {memories}

            Question: {question}

            Answer:""")
    String generateAnswer(@V("memories") String memories, @V("question") String question);
}
