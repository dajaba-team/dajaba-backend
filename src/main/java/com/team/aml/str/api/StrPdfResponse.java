package com.team.aml.str.api;

import com.team.aml.str.application.StrPdfFile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * PDF 응답 (S7·S8). 브라우저 새 탭에서 바로 열리게 inline, 파일 이름은 문서번호.
 * <ul>
 *   <li>Cache-Control no-store — 본문 전체가 든 문서라 브라우저·프록시 캐시에 남기지 않는다(결정 4 열람 제한)</li>
 *   <li>매핑에 produces 를 걸지 않는다 — 걸면 에러(404·403·409 JSON)를 application/pdf 로 못 바꿔 406 이 된다.
 *       성공일 때만 여기서 Content-Type 을 정한다</li>
 * </ul>
 */
final class StrPdfResponse {

    private StrPdfResponse() {}

    static ResponseEntity<byte[]> inline(StrPdfFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.fileName()).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }
}
