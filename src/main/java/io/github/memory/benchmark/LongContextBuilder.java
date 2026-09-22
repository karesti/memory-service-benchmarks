package io.github.memory.benchmark;

import io.github.memory.benchmark.locomo.LoCoMoDataset;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds truncated conversation context for long-context mode.
 * Packs conversation history into the LLM's context window according to a token budget
 * and truncation strategy.
 */
@ApplicationScoped
public class LongContextBuilder {
    private static final Logger log = Logger.getLogger(LongContextBuilder.class);
    
    @Inject
    TokenCounter tokenCounter;
    
    /**
     * Builds a conversation context string from the given conversation, question, and configuration.
     * The context includes a start prompt, session blocks with dates, and conversation turns.
     * Truncation is applied based on the mode and token budget.
     * 
     * @param conversation the conversation to build context from
     * @param question the question being asked (used for token budget calculation)
     * @param truncationMode how to truncate if over budget
     * @param maxTokens maximum tokens for the entire context
     * @param answerTokenBudget tokens reserved for the model's answer
     * @param batchSize number of questions being answered in one prompt (usually 1)
     * @return the formatted conversation context string
     */
    public String buildContext(
            LoCoMoDataset.Conversation conversation,
            String question,
            TruncationMode truncationMode,
            int maxTokens,
            int answerTokenBudget,
            int batchSize
    ) {
        String speakerA = conversation.speakerA();
        String speakerB = conversation.speakerB();
        
        // Start prompt
        String startPrompt = String.format(
            "Below is a conversation between two people: %s and %s. " +
            "It takes place over multiple days; each part is dated.\n\n",
            speakerA, speakerB
        );
        
        // Calculate effective budget
        int reserve = maxTokens - (answerTokenBudget * batchSize);
        int questionTokens = tokenCounter.count(question);
        int running = questionTokens + tokenCounter.count(startPrompt);
        
        // NONE mode: include everything without truncation
        if (truncationMode == TruncationMode.NONE) {
            StringBuilder body = new StringBuilder();
            for (LoCoMoDataset.Session session : conversation.sessions()) {
                body.append(renderSessionBlock(session));
                body.append("\n\n");
            }
            String result = startPrompt + body.toString().trim();
            
            int totalTokens = tokenCounter.count(result);
            log.infof("Built full context (NONE mode): %d tokens, %d sessions, %d total turns",
                totalTokens, conversation.sessions().size(), 
                conversation.sessions().stream().mapToInt(s -> s.turns().size()).sum());
            
            return result;
        }
        
        // Get sessions in the order we want to process them
        List<LoCoMoDataset.Session> sessions = new ArrayList<>(conversation.sessions());
        if (truncationMode == TruncationMode.KEEP_NEWEST) {
            Collections.reverse(sessions); // Process newest first
        }
        
        List<String> keptBlocks = new ArrayList<>();
        int keptSessions = 0;
        int keptTurns = 0;
        
        // Pack sessions until budget is exhausted
        outer:
        for (LoCoMoDataset.Session session : sessions) {
            String header = buildSessionHeader(session);
            int headerTokens = tokenCounter.count(header);
            
            // Check if we can fit at least the header
            if (running + headerTokens >= reserve) {
                break; // Can't fit this session at all
            }
            
            // Process turns in the order we want to keep them
            List<LoCoMoDataset.Turn> turns = session.turns();
            List<String> sessionTurnLines = new ArrayList<>();
            
            for (LoCoMoDataset.Turn turn : turns) {
                String line = turn.speaker() + " said, \"" + turn.text() + "\"";
                int lineTokens = tokenCounter.count(line);
                
                if (running + headerTokens + lineTokens < reserve) {
                    running += lineTokens;
                    sessionTurnLines.add(line);
                    keptTurns++;
                } else {
                    // Budget exhausted - stop processing
                    break outer;
                }
            }
            
            // If we kept any turns from this session, add the block
            if (!sessionTurnLines.isEmpty()) {
                StringBuilder block = new StringBuilder();
                block.append(header).append("\n");
                for (String line : sessionTurnLines) {
                    block.append(line).append("\n");
                }
                keptBlocks.add(block.toString().trim());
                running += headerTokens;
                keptSessions++;
            }
        }
        
        // Restore chronological order for display
        if (truncationMode == TruncationMode.KEEP_NEWEST) {
            Collections.reverse(keptBlocks);
        }
        
        String body = String.join("\n\n", keptBlocks);
        String result = startPrompt + body;
        
        int totalSessions = conversation.sessions().size();
        int totalTurns = conversation.sessions().stream().mapToInt(s -> s.turns().size()).sum();
        
        log.infof("Built context (%s mode): %d tokens, kept %d/%d sessions, %d/%d turns",
            truncationMode, tokenCounter.count(result), keptSessions, totalSessions, 
            keptTurns, totalTurns);
        
        return result;
    }
    
    /**
     * Renders a complete session block with header and turns.
     * Used for NONE mode where no truncation is needed.
     */
    private String renderSessionBlock(LoCoMoDataset.Session session) {
        StringBuilder block = new StringBuilder();
        block.append(buildSessionHeader(session)).append("\n");
        
        for (LoCoMoDataset.Turn turn : session.turns()) {
            block.append(turn.speaker()).append(" said, \"").append(turn.text()).append("\"\n");
        }
        
        return block.toString().trim();
    }
    
    /**
     * Builds the header for a session block.
     * Includes the date/time if available.
     */
    private String buildSessionHeader(LoCoMoDataset.Session session) {
        if (session.dateTime() != null && !session.dateTime().isBlank()) {
            return "DATE: " + session.dateTime() + "\nCONVERSATION:";
        } else {
            return "CONVERSATION:";
        }
    }
}
