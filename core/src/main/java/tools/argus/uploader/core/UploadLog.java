package tools.argus.uploader.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A plain-text record of one upload run: when each request started, how long its body took to send
 * and the server to reply, the status, any rate-limit-looking response headers, and a summary at
 * the end. Only response headers are logged; the request (and so the token) never is. Does nothing
 * until {@link #setDirectory} is called, and turns itself off after an I/O error.
 */
public final class UploadLog {

    private static final int KEEP_FILES = 30;
    private static final int BODY_SNIPPET = 300;
    private static final int FULL_HEADER_RESPONSES = 3;
    private static final Pattern RATE_HEADER = Pattern.compile("(?i)rate|limit|retry|remaining|reset|quota|queue|backlog|throttle|wait|position");
    private static final Pattern SECRET_HEADER = Pattern.compile("(?i)set-cookie|authorization");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static volatile Path directory;

    private BufferedWriter out;
    private final long startedNanos = System.nanoTime();
    private int responses;
    private int started;
    private int peakOutstanding;
    private int pauses;
    private final List<Long> replyMillis = new ArrayList<>();
    private final List<Long> bodyMillis = new ArrayList<>();
    private final Map<String, Integer> outcomes = new TreeMap<>();

    private UploadLog(BufferedWriter out) {
        this.out = out;
    }

    public static void setDirectory(Path logDirectory) {
        directory = logDirectory;
    }

    static UploadLog open(String runId, String description) {
        Path dir = directory;
        if (dir == null) {
            return new UploadLog(null);
        }
        try {
            Files.createDirectories(dir);
            prune(dir);
            Path file = dir.resolve(LocalDateTime.now().format(FILE_STAMP) + "-" + runId.replaceAll("[^A-Za-z0-9._-]", "_") + ".log");
            UploadLog log = new UploadLog(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
            log.raw("run " + runId + " at " + LocalDateTime.now() + "\n" + description + "\n");
            return log;
        } catch (IOException e) {
            return new UploadLog(null);
        }
    }

    private static void prune(Path dir) throws IOException {
        List<Path> logs;
        try (Stream<Path> files = Files.list(dir)) {
            logs = new ArrayList<>(files.filter(p -> p.getFileName().toString().endsWith(".log")).toList());
        }
        Collections.sort(logs);
        for (int i = 0; i < logs.size() - (KEEP_FILES - 1); i++) {
            Files.deleteIfExists(logs.get(i));
        }
    }

    synchronized void requestStarted(RegionFile region, String batchId, int attempt, int outstanding, int sending) {
        started++;
        peakOutstanding = Math.max(peakOutstanding, outstanding);
        line("START", region.filename() + " " + region.dimension() + " " + region.sizeBytes() + "B batch=" + batchId
                + " try=" + attempt + " open=" + outstanding + " sending=" + sending);
    }

    synchronized void requestFinished(RegionFile region, ArgusUploadClient.UploadResult result) {
        if (result == null) {
            line("CANCEL", region.filename());
            return;
        }
        responses++;
        if (result.totalMillis() >= 0) {
            replyMillis.add(result.totalMillis());
        }
        if (result.bodySentMillis() >= 0) {
            bodyMillis.add(result.bodySentMillis());
        }
        String timing = "sent=" + result.bodySentMillis() + "ms total=" + result.totalMillis() + "ms";
        if (result.ioError() != null) {
            outcomes.merge("io-error", 1, Integer::sum);
            line("IOERR", region.filename() + " " + timing + " " + result.ioError().getClass().getSimpleName()
                    + ": " + result.ioError().getMessage());
            return;
        }
        outcomes.merge(Integer.toString(result.statusCode()), 1, Integer::sum);
        boolean full = responses <= FULL_HEADER_RESPONSES || !result.success();
        StringBuilder headers = new StringBuilder();
        for (Map.Entry<String, String> header : result.headers().entrySet()) {
            String name = header.getKey();
            if (SECRET_HEADER.matcher(name).find() || !(full || RATE_HEADER.matcher(name).find())) {
                continue;
            }
            headers.append(headers.length() == 0 ? "" : "; ").append(name).append('=').append(header.getValue());
        }
        String body = result.body() == null ? "" : result.body().replaceAll("[\\r\\n]+", " ");
        if (body.length() > BODY_SNIPPET) {
            body = body.substring(0, BODY_SNIPPET) + "...";
        }
        line("REPLY", region.filename() + " status=" + result.statusCode() + " " + timing
                + (headers.length() > 0 ? " headers[" + headers + "]" : "") + " body=" + body);
    }

    synchronized void paused(int statusCode, long millis) {
        pauses++;
        line("PAUSE", "HTTP " + statusCode + ": holding new requests for " + millis + "ms");
    }

    synchronized void retrying(RegionFile region, int nextAttempt, long backoffMillis) {
        line("RETRY", region.filename() + " try=" + nextAttempt + " in " + backoffMillis + "ms");
    }

    synchronized void failed(RegionFile region, String reason) {
        line("FAILED", region.filename() + " " + reason);
    }

    synchronized void note(String event, String detail) {
        line(event, detail);
    }

    synchronized void finish(int succeeded, int failed, boolean cancelled) {
        double wallSeconds = (System.nanoTime() - startedNanos) / 1e9;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%s: %d ok, %d failed in %.1fs", cancelled ? "cancelled" : "finished",
                succeeded, failed, wallSeconds));
        if (wallSeconds > 0) {
            sb.append(String.format(Locale.ROOT, " (%.1f regions/min)", succeeded * 60.0 / wallSeconds));
        }
        sb.append("\nrequests started: ").append(started).append(", peak open at once: ").append(peakOutstanding)
                .append(", rate-limit pauses: ").append(pauses);
        sb.append("\noutcomes: ").append(outcomes);
        sb.append("\nreply time (ms): ").append(percentiles(replyMillis));
        sb.append("\nbody sent time (ms): ").append(percentiles(bodyMillis));
        line("DONE", sb.toString());
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
            // nothing left to write
        }
        out = null;
    }

    private static String percentiles(List<Long> values) {
        if (values.isEmpty()) {
            return "none";
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return "min " + sorted.get(0) + ", median " + sorted.get(n / 2) + ", p90 " + sorted.get(Math.min(n - 1, (int) (n * 0.9)))
                + ", max " + sorted.get(n - 1) + " (n=" + n + ")";
    }

    private void line(String event, String detail) {
        double seconds = (System.nanoTime() - startedNanos) / 1e9;
        raw(String.format(Locale.ROOT, "+%9.3fs %-6s %s%n", seconds, event, detail));
    }

    private void raw(String text) {
        if (out == null) {
            return;
        }
        try {
            out.write(text);
            out.flush();
        } catch (IOException e) {
            out = null;
        }
    }
}
