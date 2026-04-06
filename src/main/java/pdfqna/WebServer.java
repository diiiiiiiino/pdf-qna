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
import java.util.stream.*;

public class WebServer {

    private static final String SCRIPT_DIR = System.getProperty("user.dir");
    private static final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    static class JobState {
        final StringBuilder output = new StringBuilder();
        volatile boolean done = false;
        boolean useMd = false;
        String mode = "split";
        String mdOutDir = null;
        final List<String> pdfBasenames = new ArrayList<>();
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
        app.get("/api/files/{jobId}", WebServer::handleFiles);
        app.get("/api/download", WebServer::handleDownload);
    }

    private static void handleRun(Context ctx) {
        try {
            List<UploadedFile> uploadedFiles = ctx.uploadedFiles("file");
            String mode = ctx.formParam("mode");
            String notionUrl = ctx.formParam("notionUrl");
            String outputType = ctx.formParam("outputType"); // "notion", "md", "both"
            String mdOutDir = ctx.formParam("mdOutDir");

            if (uploadedFiles.isEmpty()) {
                ctx.status(400).json(Map.of("error", "PDF 파일을 업로드해주세요."));
                return;
            }

            if (outputType == null || outputType.isBlank()) {
                outputType = "notion"; // 기본값: Notion 업로드
            }

            boolean useNotion = "notion".equals(outputType) || "both".equals(outputType);
            boolean useMd = "md".equals(outputType) || "both".equals(outputType);

            if (useNotion && (notionUrl == null || notionUrl.isBlank())) {
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
            if (useNotion) {
                cmd.add("--notion");
                cmd.add(notionUrl);
            }
            if (useMd) {
                cmd.add("--md");
                if (mdOutDir != null && !mdOutDir.isBlank()) {
                    cmd.add("--md-dir");
                    cmd.add(mdOutDir);
                }
            }

            for (Path tf : tempFiles) {
                cmd.add(tf.toString());
            }

            String jobId = UUID.randomUUID().toString().substring(0, 8);
            JobState state = new JobState();
            state.useMd = useMd;
            state.mode = mode != null ? mode : "split";
            state.mdOutDir = (mdOutDir != null && !mdOutDir.isBlank()) ? mdOutDir : null;
            for (UploadedFile uf : uploadedFiles) {
                String name = uf.filename();
                if (name.toLowerCase().endsWith(".pdf")) {
                    name = name.substring(0, name.length() - 4);
                }
                state.pdfBasenames.add(name);
            }
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

        Map<String, Object> result = new HashMap<>();
        result.put("output", state.output.toString());
        result.put("done", state.done);
        result.put("useMd", state.useMd);
        ctx.json(result);

        // done 상태여도 파일 다운로드를 위해 바로 제거하지 않음
    }

    private static void handleFiles(Context ctx) {
        String jobId = ctx.pathParam("jobId");
        JobState state = jobs.get(jobId);

        if (state == null) {
            ctx.status(404).json(Map.of("error", "Job not found"));
            return;
        }

        if (!state.done || !state.useMd) {
            ctx.json(Map.of("files", List.of()));
            return;
        }

        List<Map<String, String>> files = new ArrayList<>();

        for (String basename : state.pdfBasenames) {
            Path qnaDir;
            if (state.mdOutDir != null) {
                qnaDir = Paths.get(state.mdOutDir);
            } else {
                qnaDir = Paths.get(SCRIPT_DIR, "out", basename + "_qna");
            }
            if (Files.isDirectory(qnaDir)) {
                try (var stream = Files.list(qnaDir)) {
                    Path baseDir = qnaDir;
                    stream.filter(p -> p.toString().endsWith(".md"))
                          .sorted()
                          .forEach(p -> files.add(Map.of(
                                  "name", p.getFileName().toString(),
                                  "path", baseDir.resolve(p.getFileName()).toString()
                          )));
                } catch (IOException ignored) {}
            }
        }

        ctx.json(Map.of("files", files));

        // 파일 목록 조회 후 job 정리
        jobs.remove(jobId);
    }

    private static void handleDownload(Context ctx) {
        String filePath = ctx.queryParam("path");
        if (filePath == null || filePath.isBlank()) {
            ctx.status(400).result("path 파라미터가 필요합니다.");
            return;
        }

        Path resolved = Paths.get(filePath).normalize();
        // 상대 경로인 경우 SCRIPT_DIR 기준으로 해석
        if (!resolved.isAbsolute()) {
            resolved = Paths.get(SCRIPT_DIR).resolve(filePath).normalize();
        }

        // .md 파일만 허용
        if (!resolved.toString().endsWith(".md")) {
            ctx.status(403).result("마크다운 파일만 다운로드할 수 있습니다.");
            return;
        }

        if (!Files.exists(resolved)) {
            ctx.status(404).result("파일을 찾을 수 없습니다.");
            return;
        }

        ctx.header("Content-Type", "text/markdown; charset=utf-8");
        ctx.header("Content-Disposition", "attachment; filename=\"" + resolved.getFileName() + "\"");
        try {
            ctx.result(Files.newInputStream(resolved));
        } catch (IOException e) {
            ctx.status(500).result("파일 읽기 오류: " + e.getMessage());
        }
    }
}