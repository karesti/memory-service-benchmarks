package io.github.chirino.memory.benchmark;

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
            - WRONG if the answer contradicts the ground truth, is missing critical facts, or says "I don't know" when the ground truth has a clear answer.
            - WRONG if the answer fabricates information not in the ground truth.
            - For dates/numbers: minor format differences are OK (e.g., "May 7" vs "7 May 2023"), but wrong values are WRONG.

            Question: {question}

            Ground truth answer: {groundTruth}

            Generated answer: {generatedAnswer}

            Verdict (JSON only):""")
    String judge(@V("question") String question, @V("groundTruth") String groundTruth, @V("generatedAnswer") String generatedAnswer);
}
