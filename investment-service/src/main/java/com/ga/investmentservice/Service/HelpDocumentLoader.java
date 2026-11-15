package com.ga.investmentservice.Service;

import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "ai.help.enabled", havingValue = "true")
public class HelpDocumentLoader {
    private static final String source = "help/user-guide.md";
    private static final Pattern heading = Pattern.compile("^(#{1,6}) +(.+?) *#* *$");

    public List<Document> load() throws IOException {
        try (var input = new ClassPathResource(source).getInputStream()) {
            return split(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    List<Document> split(String markdown) {
        List<Document> documents = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            var match = heading.matcher(line);
            if (match.matches()) {
                addSection(documents, headings, body, ids);
                int level = match.group(1).length();
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    headings.remove(headings.size() - 1);
                }
                levels.add(level);
                headings.add(match.group(2));
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        addSection(documents, headings, body, ids);
        return List.copyOf(documents);
    }

    private void addSection(List<Document> documents, List<String> headings, StringBuilder body, Set<String> ids) {
        if (headings.isEmpty() || body.toString().isBlank()) return;
        String path = String.join(" > ", headings);
        String id = "user-guide-" + hash(path);
        if (!ids.add(id)) throw new IllegalStateException("Duplicate help section heading");
        documents.add(Document.builder().id(id).text(path + "\n\n" + body.toString().strip())
                .metadata(Map.of("source", source, "sourceId", id,
                        "title", headings.get(headings.size() - 1), "sectionPath", path)).build());
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
