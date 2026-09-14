import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Replace exactly the two compiled handlers; retain every other entry's contents. */
public final class PatchJar {
    public static void main(String[] args) throws Exception {
        String prefix = "org/apache/tinkerpop/gremlin/server/handler/";
        Set<String> replacements = new HashSet<>(Arrays.asList(
                prefix + "WebSocketAuthorizationHandler.class",
                prefix + "HttpBasicAuthorizationHandler.class"));
        try (ZipFile source = new ZipFile(args[0]);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            List<? extends ZipEntry> entries = Collections.list(source.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            Set<String> seen = new HashSet<>();
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!seen.add(name) || name.matches("META-INF/.*\\.(SF|RSA|DSA|EC)"))
                    throw new IllegalStateException("Duplicate entry or signed input JAR");
                ZipEntry target = new ZipEntry(name);
                target.setTimeLocal(java.time.LocalDateTime.of(2026, 9, 14, 0, 0));
                output.putNextEntry(target);
                if (replacements.remove(name)) {
                    Files.copy(Path.of(args[1], name), output);
                } else {
                    try (InputStream data = source.getInputStream(entry)) { data.transferTo(output); }
                }
                output.closeEntry();
            }
            if (!replacements.isEmpty()) throw new IllegalStateException("Missing original handler");
        }
    }
}
