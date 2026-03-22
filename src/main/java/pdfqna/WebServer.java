package pdfqna;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class WebServer {

    private static final String SCRIPT_DIR = System.getProperty("user.dir");
    private static final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    static class JobState {
        final StringBuilder output = new StringBuilder();
        volatile boolean done = false;
    }

    public static void main(String[] args) {
        int port = 8080;

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static", Location.CLASSPATH);
            config.http.maxRequestSize = 300_000_000L;
        }).start(port);

        System.out.println("PDF Q&A 서버 시작: http://localhost:" + port);

        app.post("/api/run", WebServer::handleRun);
        app.get("/api/output/{jobId}", WebServer::handleOutput);
    }

    private static void handleRun(Context ctx) {
        try {
            List<UploadedFile> uploadedFiles = ctx.uploadedFiles("file");
            String mode = ctx.formParam("mode");
            String notionUrl = ctx.formParam("notionUrl");

            if (uploadedFiles.isEmpty()) {
                ctx.status(400).json(Map.of("error", "PDF 파일을 업로드해주세요."));
                return;
            }

            if (notionUrl == null || notionUrl.isBlank()) {
                ctx.status(400).json(Map.of("error", "Notion 페이지 URL을 입력해주세요."));
                return;
            }

            Path tempDir = Files.createTempDirectory("pdf-qna-");
            List<Path> tempFiles = new ArrayList<>();

            for (UploadedFile uf : uploadedFiles) {
                Path tempFile = tempDir.resolve(uf.filename());
                Files.copy(uf.content(), tempFile, StandardCopyOption.REPLACE_EXISTING);
                tempFiles.add(tempFile);
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("bash");
            cmd.add(SCRIPT_DIR + "/run.sh");

            if ("single".equals(mode)) {
                cmd.add("--single");
            }
            cmd.add("--notion");
            cmd.add(notionUrl);

            for (Path tf : tempFiles) {
                cmd.add(tf.toString());
            }

            String jobId = UUID.randomUUID().toString().substring(0, 8);
            JobState state = new JobState();
            jobs.put(jobId, state);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(SCRIPT_DIR));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        state.output.append(line).append("\n");
                    }
                    process.waitFor();
                } catch (Exception e) {
                    state.output.append("\n오류: ").append(e.getMessage()).append("\n");
                } finally {
                    state.done = true;
                    try {
                        for (Path tf : tempFiles) {
                            Files.deleteIfExists(tf);
                        }
                        Files.deleteIfExists(tempDir);
                    } catch (IOException ignored) {
                    }
                }
            }).start();

            ctx.json(Map.of("jobId", jobId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private static void handleOutput(Context ctx) {
        String jobId = ctx.pathParam("jobId");
        JobState state = jobs.get(jobId);

        if (state == null) {
            ctx.status(404).json(Map.of("error", "Job not found"));
            return;
        }

        ctx.json(Map.of(
                "output", state.output.toString(),
                "done", state.done
        ));

        if (state.done) {
            jobs.remove(jobId);
        }
    }
}