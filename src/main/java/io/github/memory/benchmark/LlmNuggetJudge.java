package io.github.memory.benchmark;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface LlmNuggetJudge {

    @UserMessage("""
            You are an evaluation judge. You must determine whether a model response satisfies
            a specific rubric criterion for a given question.

            Score the response:
            - 1.0 if the criterion is fully satisfied
            - 0.5 if the criterion is partially satisfied (some relevant info but incomplete or imprecise)
            - 0.0 if the criterion is not satisfied at all

            Respond with ONLY a JSON object: {"score": 0.0 or 0.5 or 1.0, "reason": "brief explanation"}

            Rules:
            - Semantic equivalence counts — different wording with the same meaning is a match.
            - More specific or detailed responses that cover the criterion are fully satisfied.
            - If the criterion says "should state X" and the response says X in different words, score 1.0.
            - If the criterion says "should contain" something and only part of it is present, score 0.5.
            - If the response contradicts the criterion or omits it entirely, score 0.0.

            Question: {question}

            Rubric criterion: {rubric}

            Model response: {response}

            Score (JSON only):""")
    String judgeNugget(@V("question") String question, @V("rubric") String rubric, @V("response") String response);
}
