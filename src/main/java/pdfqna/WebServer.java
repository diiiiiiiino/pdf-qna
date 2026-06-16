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
        String taskType = "qna";
        final List<String> pdfBasenames = new ArrayList<>();
        final List<String> categories = new ArrayList<>(); // pdfBasenames와 평행. 카테고리 없으면 빈 문자열
    }

    private static Path sourceRoot() {
        return Paths.get(SCRIPT_DIR, "source");
    }

    private static Path resultRoot() {
        return Paths.get(SCRIPT_DIR, "result");
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
        app.get("/api/converted", WebServer::handleConverted);
    }

    private static void handleConverted(Context ctx) {
        Path root = sourceRoot().toAbsolutePath().normalize();
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                      .forEach(p -> {
                          Map<String, Object> entry = new HashMap<>();
                          String fname = p.getFileName().toString();
                          Path rel = root.relativize(p);
                          Path parent = rel.getParent();
                          String category = parent == null ? "" : parent.toString();
                          entry.put("name", fname);
                          entry.put("relPath", rel.toString());
                          entry.put("category", category);
                          try {
                              entry.put("size", Files.size(p));
                              entry.put("modified", Files.getLastModifiedTime(p).toMillis());
                          } catch (IOException ignored) {
                              entry.put("size", 0);
                              entry.put("modified", 0);
                          }
                          files.add(entry);
                      });
            } catch (IOException e) {
                ctx.status(500).json(Map.of("error", e.getMessage()));
                return;
            }
        }
        // 카테고리(미분류 먼저) → 이름 순 정렬
        files.sort((a, b) -> {
            String ca = (String) a.get("category");
            String cb = (String) b.get("category");
            int cmp = ca.compareTo(cb);
            if (cmp != 0) return cmp;
            return ((String) a.get("name")).compareTo((String) b.get("name"));
        });
        ctx.json(Map.of("files", files));
    }

    private static void handleRun(Context ctx) {
        try {
            String inputMode = ctx.formParam("inputMode"); // "pdf" (default) or "md"
            if (inputMode == null || inputMode.isBlank()) {
                inputMode = "pdf";
            }
            boolean fromMd = "md".equals(inputMode);

            List<UploadedFile> uploadedFiles = fromMd ? List.of() : ctx.uploadedFiles("file");
            List<String> mdPathsParam = fromMd ? ctx.formParams("mdPaths") : List.of();

            String mode = ctx.formParam("mode");
            String notionUrl = ctx.formParam("notionUrl");
            String outputType = ctx.formParam("outputType"); // "notion", "md", "both"
            String mdOutDir = ctx.formParam("mdOutDir");
            String taskType = ctx.formParam("taskType"); // "qna" or "summary"

            if (!fromMd && uploadedFiles.isEmpty()) {
                ctx.status(400).json(Map.of("error", "PDF 파일을 업로드해주세요."));
                return;
            }
            if (fromMd && mdPathsParam.isEmpty()) {
                ctx.status(400).json(Map.of("error", "사용할 MD 파일을 선택해주세요."));
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

            List<Path> inputPaths = new ArrayList<>();
            List<String> basenames = new ArrayList<>();
            Path tempDir = null;
            List<Path> tempFiles = new ArrayList<>();

            List<String> categories = new ArrayList<>();
            if (fromMd) {
                Path root = sourceRoot().toAbsolutePath().normalize();
                for (String raw : mdPathsParam) {
                    if (raw == null || raw.isBlank()) continue;
                    String relPath = raw.endsWith(".md") ? raw : raw + ".md";
                    Path resolved = root.resolve(relPath).normalize();
                    if (!resolved.startsWith(root)) {
                        ctx.status(400).json(Map.of("error", "잘못된 파일 경로: " + raw));
                        return;
                    }
                    if (!Files.isRegularFile(resolved)) {
                        ctx.status(404).json(Map.of("error", "파일을 찾을 수 없습니다: " + relPath));
                        return;
                    }
                    inputPaths.add(resolved);
                    String fname = resolved.getFileName().toString();
                    String base = fname.substring(0, fname.length() - 3);
                    basenames.add(base);
                    Path rel = root.relativize(resolved);
                    Path parent = rel.getParent();
                    categories.add(parent == null ? "" : parent.toString());
                }
            } else {
                tempDir = Files.createTempDirectory("pdf-qna-");
                for (UploadedFile uf : uploadedFiles) {
                    Path tempFile = tempDir.resolve(uf.filename());
                    Files.copy(uf.content(), tempFile, StandardCopyOption.REPLACE_EXISTING);
                    tempFiles.add(tempFile);
                    inputPaths.add(tempFile);
                    String name = uf.filename();
                    if (name.toLowerCase().endsWith(".pdf")) {
                        name = name.substring(0, name.length() - 4);
                    }
                    basenames.add(name);
                    categories.add(""); // PDF 업로드는 카테고리 없음
                }
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("bash");
            cmd.add(SCRIPT_DIR + "/run.sh");

            if ("single".equals(mode)) {
                cmd.add("--single");
            }
            if ("summary".equals(taskType)) {
                cmd.add("--summary");
            } else if ("convert".equals(taskType)) {
                cmd.add("--convert");
            } else if ("deck".equals(taskType)) {
                cmd.add("--deck");
            }
            if (fromMd) {
                cmd.add("--from-md");
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

            for (Path p : inputPaths) {
                cmd.add(p.toString());
            }

            String jobId = UUID.randomUUID().toString().substring(0, 8);
            JobState state = new JobState();
            state.useMd = useMd;
            state.mode = mode != null ? mode : "split";
            state.taskType = taskType != null ? taskType : "qna";
            state.mdOutDir = (mdOutDir != null && !mdOutDir.isBlank()) ? mdOutDir : null;
            state.pdfBasenames.addAll(basenames);
            state.categories.addAll(categories);
            jobs.put(jobId, state);

            final Path finalTempDir = tempDir;
            final List<Path> finalTempFiles = tempFiles;

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
                    if (finalTempDir != null) {
                        try {
                            for (Path tf : finalTempFiles) {
                                Files.deleteIfExists(tf);
                            }
                            Files.deleteIfExists(finalTempDir);
                        } catch (IOException ignored) {
                        }
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
        Path resultRoot = resultRoot().resolve(state.taskType);

        for (int i = 0; i < state.pdfBasenames.size(); i++) {
            String basename = state.pdfBasenames.get(i);
            String category = i < state.categories.size() ? state.categories.get(i) : "";
            Path qnaDir;
            if (state.mdOutDir != null) {
                qnaDir = Paths.get(state.mdOutDir);
            } else if (category != null && !category.isEmpty()) {
                qnaDir = resultRoot.resolve(category).resolve(basename);
            } else {
                qnaDir = resultRoot.resolve(basename);
            }
            if (Files.isDirectory(qnaDir)) {
                try (var stream = Files.list(qnaDir)) {
                    Path baseDir = qnaDir;
                    String fileExt = "deck".equals(state.taskType) ? ".json" : ".md";
                    stream.filter(p -> p.toString().endsWith(fileExt))
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

        // .md 또는 .json 파일만 허용
        String fileName = resolved.toString();
        if (!fileName.endsWith(".md") && !fileName.endsWith(".json")) {
            ctx.status(403).result("마크다운 또는 JSON 파일만 다운로드할 수 있습니다.");
            return;
        }

        if (!Files.exists(resolved)) {
            ctx.status(404).result("파일을 찾을 수 없습니다.");
            return;
        }

        String contentType = fileName.endsWith(".json") ? "application/json; charset=utf-8" : "text/markdown; charset=utf-8";
        ctx.header("Content-Type", contentType);
        ctx.header("Content-Disposition", "attachment; filename=\"" + resolved.getFileName() + "\"");
        try {
            ctx.result(Files.newInputStream(resolved));
        } catch (IOException e) {
            ctx.status(500).result("파일 읽기 오류: " + e.getMessage());
        }
    }
}