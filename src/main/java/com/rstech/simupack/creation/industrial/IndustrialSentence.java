/*
 * Decompiled with CFR.
 */
package com.rstech.simupack.creation.industrial;

import com.rstech.simupack.creation.industrial.IndustrialStepType;
import com.rstech.simupack.creation.json.DefinitionField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IndustrialSentence {
    private static final Pattern TOKEN = Pattern.compile("[{]([a-zA-Z][a-zA-Z0-9]*)[}]");

    private IndustrialSentence() {
    }

    public static List<Part> parse(String text) {
        Matcher matcher = Pattern.compile("[{]([a-zA-Z][a-zA-Z0-9]*)[}]").matcher(text);
        ArrayList<Part> result = new ArrayList<Part>();
        int start = 0;
        while (matcher.find()) {
            if (matcher.start() > start) {
                result.add(new Part(text.substring(start, matcher.start()), null));
            }
            result.add(new Part(null, matcher.group(1)));
            start = matcher.end();
        }
        if (start < text.length()) {
            result.add(new Part(text.substring(start), null));
        }
        return List.copyOf(result);
    }

    public static List<DefinitionField> fields(IndustrialStepType type) {
        LinkedHashMap<String, DefinitionField> unique = new LinkedHashMap<String, DefinitionField>();
        for (DefinitionField field : type.fields()) {
            unique.putIfAbsent(field.key(), field);
        }
        return List.copyOf(unique.values());
    }

    public record Part(String text, String field) {
    }
}

