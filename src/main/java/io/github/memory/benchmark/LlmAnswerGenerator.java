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

            INSTRUCTIONS:
            1. Scan ALL memories carefully before answering — the answer may be in any of them, not just the top ones.
            2. Many questions require combining information from MULTIPLE memories.
            3. Reason and draw logical conclusions:
               - "moved from her home country" + context clues → infer the country
               - "went camping the week before June 27" → camping was around June 2023
               - "realized its importance after the race" → connect the event to the realization
            4. If a memory contains even partial or indirect evidence, use it to form your best answer.
            5. NEVER say "Not mentioned" unless you have checked every single memory and found
               absolutely nothing related to the question — not even indirect references.
            6. Prefer a specific, concise answer (a name, date, place, or short phrase) over a long explanation.

            Retrieved memories:
            {memories}

            Question: {question}

            Answer:""")
    String generateAnswer(@V("memories") String memories, @V("question") String question);
}
