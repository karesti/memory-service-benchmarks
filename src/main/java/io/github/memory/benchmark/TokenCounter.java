package io.github.memory.benchmark;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Utility for counting tokens in text using BPE (Byte Pair Encoding).
 * Uses JTokkit library for accurate token counting compatible with OpenAI models.
 * Falls back to character-based estimation if encoding is unavailable.
 */
@ApplicationScoped
public class TokenCounter {
    private static final Logger log = Logger.getLogger(TokenCounter.class);
    
    private final Encoding encoding;
    private final String encodingName;
    private final boolean fallbackMode;
    
    /**
     * Creates a TokenCounter with the specified encoding.
     * Falls back to character-based estimation if encoding is not available.
     * 
     * @param encodingName the name of the encoding (e.g., "cl100k_base", "p50k_base")
     */
    public TokenCounter(String encodingName) {
        this.encodingName = encodingName;
        Encoding enc = null;
        boolean fallback = false;
        
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            
            // Try to get encoding by EncodingType first
            try {
                EncodingType type = EncodingType.valueOf(encodingName.toUpperCase());
                enc = registry.getEncoding(type);
                if (enc != null) {
                    log.infof("Initialized TokenCounter with encoding type: %s", encodingName);
                } else {
                    log.warnf("Encoding type '%s' returned null, falling back to character-based estimation", encodingName);
                    fallback = true;
                }
            } catch (IllegalArgumentException e) {
                // Not a valid EncodingType, try as string name
                try {
                    var optEnc = registry.getEncoding(encodingName);
                    enc = optEnc.orElse(null);
                    if (enc != null) {
                        log.infof("Initialized TokenCounter with encoding name: %s", encodingName);
                    } else {
                        log.warnf("Encoding '%s' returned null, falling back to character-based estimation", encodingName);
                        fallback = true;
                    }
                } catch (Exception e2) {
                    log.warnf("Encoding '%s' not found, falling back to character-based estimation", encodingName);
                    fallback = true;
                }
            } catch (Exception e) {
                log.warnf("Encoding type '%s' not available, falling back to character-based estimation", encodingName);
                fallback = true;
            }
        } catch (Exception e) {
            log.warnf(e, "Failed to initialize encoding '%s', falling back to character-based estimation", encodingName);
            fallback = true;
        }
        
        this.encoding = enc;
        this.fallbackMode = fallback;
    }
    
    /**
     * Default constructor using cl100k_base encoding (GPT-4, GPT-3.5-turbo).
     */
    public TokenCounter() {
        this("cl100k_base");
    }
    
    /**
     * Counts the number of tokens in the given text.
     * Uses BPE encoding if available, otherwise estimates based on character count.
     * 
     * @param text the text to count tokens for
     * @return the number of tokens
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        if (fallbackMode) {
            // Fallback: estimate ~4 characters per token (conservative estimate)
            return (int) Math.ceil(text.length() / 4.0);
        }
        
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            log.warnf(e, "Error counting tokens, falling back to character-based estimation");
            return (int) Math.ceil(text.length() / 4.0);
        }
    }
    
    /**
     * Checks if the specified encoding is supported.
     * 
     * @param encodingName the encoding name to check
     * @return true if the encoding is supported, false otherwise
     */
    public static boolean isSupported(String encodingName) {
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            
            // Try as EncodingType
            try {
                EncodingType type = EncodingType.valueOf(encodingName.toUpperCase());
                registry.getEncoding(type);
                return true;
            } catch (IllegalArgumentException e) {
                // Try as string name
                try {
                    registry.getEncoding(encodingName);
                    return true;
                } catch (Exception e2) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Returns the encoding name used by this counter.
     * 
     * @return the encoding name
     */
    public String getEncodingName() {
        return encodingName;
    }
    
    /**
     * Returns whether this counter is in fallback mode (character-based estimation).
     * 
     * @return true if in fallback mode, false if using BPE encoding
     */
    public boolean isFallbackMode() {
        return fallbackMode;
    }
}