package com.elia.evoconti;

interface LocalLlmEngine {
    boolean isAvailable();
    AiAnswer ask(AiContext context, String userQuestion);
}
