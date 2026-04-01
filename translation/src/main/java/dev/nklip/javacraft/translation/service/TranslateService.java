package dev.nklip.javacraft.translation.service;

public interface TranslateService {

    // TODO: create implementation for Google API
    // TODO: create implementation for AWS Translate

    /**
     * Doesn't provide referential transparency.
     */
    String translate2English(String word);

}
