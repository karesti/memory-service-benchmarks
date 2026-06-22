package io.github.chirino.memory.benchmark;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService
public interface LlmJudge {

    @SystemMessage("""
            You are a question-answering assistant. You will be given retrieved memory fragments
            from past conversations and a question. Answer the question using ONLY the provided
            memories. If the memories do not contain enough information, say "I don't know" or
            "Not mentioned in the provided context."
            Be concise and factual. Do not make up information.""")
    @UserMessage("""
            Retrieved memories:
            {memories}

            Question: {question}

            Answer:""")
    String generateAnswer(String memories, String question);

    @SystemMessage("""
            You are an evaluation judge. Compare a generated answer against the ground truth answer
            for a question about past conversations.

            Respond with ONLY a JSON object: {"verdict": "CORRECT" or "WRONG", "reason": "brief explanation"}

            Rules:
            - CORRECT if the generated answer captures the key facts from the ground truth, even if worded differently.
            - CORRECT if the answer is a reasonable paraphrase or contains the essential information.
            - WRONG if the answer contradicts the ground truth, is missing critical facts, or says "I don't know" when the ground truth has a clear answer.
            - WRONG if the answer fabricates information not in the ground truth.
            - For dates/numbers: minor format differences are OK (e.g., "May 7" vs "7 May 2023"), but wrong values are WRONG.""")
    @UserMessage("""
            Question: {question}

            Ground truth answer: {groundTruth}

            Generated answer: {generatedAnswer}

            Verdict (JSON only):""")
    String judge(String question, String groundTruth, String generatedAnswer);
}
