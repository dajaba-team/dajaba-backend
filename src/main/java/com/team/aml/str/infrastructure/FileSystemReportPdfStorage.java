package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.ReportPdfStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 확정본 PDF 를 로컬 폴더에 둔다 — aml.str.pdf.storage-dir (기본 ./data/str-reports, .gitignore 의 data/ 아래).
 * MinIO(str-reports 버킷)는 ops 프로필에만 있어서 평소 기동(db·app)에서도 PDF 가 되게 이것부터 둔다.
 *
 * <ul>
 *   <li>임시 파일에 다 쓴 뒤 이름을 바꾼다(ATOMIC_MOVE) — 반쯤 쓴 파일을 S8 이 읽지 않게</li>
 *   <li>key 가 폴더 밖(../)을 가리키면 거절 — key 는 우리가 만들지만 한 번 더 막는다</li>
 *   <li>도커 app 컨테이너에서는 컨테이너 안 폴더라 컨테이너를 지우면 같이 사라진다 → S8 은 500 (README·PR 참고)</li>
 * </ul>
 */
@Component
public class FileSystemReportPdfStorage implements ReportPdfStorage {

    private final Path root;

    public FileSystemReportPdfStorage(@Value("${aml.str.pdf.storage-dir:./data/str-reports}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("확정본 PDF 를 보관하지 못했습니다: " + key, e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("확정본 PDF 보관본을 읽지 못했습니다: " + key, e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new IllegalArgumentException("보관 폴더 밖을 가리키는 키: " + key);
        }
        return p;
    }
}
