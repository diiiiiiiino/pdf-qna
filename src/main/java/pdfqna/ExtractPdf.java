package pdfqna;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ExtractPdf {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("사용법: ./gradlew run --args='<pdf파일경로>'");
            System.exit(1);
        }

        String pdfPath = args[0];
        File pdfFile = new File(pdfPath);
        if (!pdfFile.isFile()) {
            System.err.println("오류: 파일을 찾을 수 없습니다: " + pdfPath);
            System.exit(1);
        }

        String baseName = pdfFile.getName().replaceFirst("\\.pdf$", "");
        String outputDir = args.length >= 2 ? args[1] : "out";

        try {
            File outDir = new File(outputDir);
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new IOException("출력 디렉토리 생성 실패: " + outputDir);
            }

            Config config = new Config();
            config.setOutputFolder(outDir.getAbsolutePath());
            config.setGenerateMarkdown(true);
            config.setGenerateJSON(false);
            config.setGenerateHtml(false);
            config.setGeneratePDF(false);

            OpenDataLoaderPDF.processFile(pdfFile.getAbsolutePath(), config);

            Path mdPath = Paths.get(outDir.getAbsolutePath(), baseName + ".md");

            if (!Files.exists(mdPath)) {
                System.out.println("SKIP:IMAGE_PDF:" + pdfPath);
                System.err.println("[알림] 이미지 기반 PDF는 지원하지 않습니다. 텍스트가 포함된 PDF를 선택해주세요: " + pdfFile.getName());
                return;
            }

            String content = new String(Files.readAllBytes(mdPath));
            boolean hasText = content.lines()
                    .anyMatch(line -> !line.isBlank() && !line.startsWith("#"));

            if (!hasText) {
                Files.deleteIfExists(mdPath);
                System.out.println("SKIP:IMAGE_PDF:" + pdfPath);
                System.err.println("[알림] 이미지 기반 PDF는 지원하지 않습니다. 텍스트가 포함된 PDF를 선택해주세요: " + pdfFile.getName());
                return;
            }

            System.out.println("OK:" + mdPath);
        } catch (Exception e) {
            System.err.println("오류: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
