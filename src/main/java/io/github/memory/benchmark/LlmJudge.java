package io.github.memory.benchmark;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface LlmJudge {

    @UserMessage("""
            You are an evaluation judge. Compare a generated answer against the ground truth answer
            for a question about past conversations.

            Respond with ONLY a JSON object: {"verdict": "CORRECT" or "WRONG", "reason": "brief explanation"}

            Rules:
            - CORRECT if the generated answer captures the key facts from the ground truth, even if worded differently.
            - CORRECT if the answer is a reasonable paraphrase or contains the essential information.
            - CORRECT if the answer is semantically equivalent (e.g., "self-care" and "mental health awareness" convey similar meaning).
            - CORRECT if extra details are provided alongside the correct answer (e.g., ground truth is "June 2023" and answer says "the week before June 27, 2023").
            - WRONG if the answer contradicts the ground truth or gives a fundamentally different answer.
            - WRONG if the answer says "I don't know" or "Not mentioned" when the ground truth has a clear answer.
            - For dates/numbers: minor format differences and approximate matches are OK (e.g., "May 7" vs "7 May 2023", "June 2023" vs "before June 27, 2023").

            Question: {question}

            Ground truth answer: {groundTruth}

            Generated answer: {generatedAnswer}

            Verdict (JSON only):""")
    String judge(@V("question") String question, @V("groundTruth") String groundTruth, @V("generatedAnswer") String generatedAnswer);
}
