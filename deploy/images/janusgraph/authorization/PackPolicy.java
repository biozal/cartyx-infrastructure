import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** Deterministic, explicitly enumerated policy JAR. No test classes or classpath overlays. */
public final class PackPolicy {
    public static void main(String[] args) throws Exception {
        List<String> classes = new ArrayList<>(List.of("IdentityChannelizer", "IdentityChannelizer$MimeGuard",
                "IdentityChannelizer$RequestGate", "IdentityGraphSONSerializer", "IdentityProfileAuthorizer"));
        Collections.sort(classes);
        Path source = Path.of(args[0], "io/cartyx/graph");
        try (java.util.stream.Stream<Path> files = Files.list(source)) {
            if (files.count() != classes.size()) throw new IllegalStateException("Unexpected policy classes");
        }
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (String name : classes) {
                ZipEntry entry = new ZipEntry("io/cartyx/graph/" + name + ".class");
                entry.setTimeLocal(LocalDateTime.of(2026, 9, 16, 0, 0)); output.putNextEntry(entry);
                Files.copy(source.resolve(name + ".class"), output); output.closeEntry();
            }
        }
    }
}
