package io.github.memory.benchmark;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * LLM service for generating answers from full conversation context in long-context mode.
 * Uses a different prompt than the retrieval-based answer generator since it works with
 * complete conversation transcripts rather than retrieved memory fragments.
 */
@ApplicationScoped
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface LlmLongContextAnswerGenerator {
    
    /**
     * Generates a short-phrase answer to a question based on the full conversation context.
     * 
     * @param conversation the complete conversation transcript with dates and turns
     * @param question the question to answer
     * @return a short-phrase answer extracted from the conversation
     */
    @SystemMessage("""
            You are a helpful assistant that answers questions based on conversation transcripts.
            Always provide short, precise answers using exact words from the conversation when possible.
            Use the DATE lines to answer questions about when something happened.
            If the answer is not in the conversation, say "Not mentioned in the conversation".
            """)
    @UserMessage("""
            {conversation}

            Based on the conversation above, write a short-phrase answer to the following question.
            Use exact words from the conversation whenever possible. Use the DATE lines to answer
            questions about when something happened. If the answer is not in the conversation, say
            "Not mentioned in the conversation".

            Question: {question}
            Short answer:""")
    String generateAnswer(@V("conversation") String conversation, @V("question") String question);
}
