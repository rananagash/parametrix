package com.parametrix;

public interface AiClient {
    String generate(String prompt, String previous, String diagnostics);
}
