# 보고서 PDF 폰트

STR 보고서 PDF(S7 미리보기·S8 확정본)에 넣는 한글 폰트예요. 코드는 `str/infrastructure/ReportPdfFonts`가 이 폴더를 읽어요.

| 파일 | 쓰는 곳 |
|---|---|
| `NanumGothic-Regular.ttf` | 본문(굵기 400) |
| `NanumGothic-Bold.ttf` | 제목·표 머리(굵기 700) |
| `OFL.txt` | 라이선스 원문 — 폰트와 같이 둬야 해요 |

- **라이선스**: SIL Open Font License 1.1 (네이버 나눔고딕). 프로그램에 넣어 배포해도 되고, 폰트 파일만 따로 팔 수는 없어요. `OFL.txt`를 지우지 마세요.
- **받은 곳**: Google Fonts 저장소 `google/fonts`의 `ofl/nanumgothic/` 폴더(`NanumGothic-Regular.ttf`·`NanumGothic-Bold.ttf`·`OFL.txt`). 파일을 바꾸지 않고 그대로 넣었어요.
- **왜 나눔고딕 TTF인가**: PDF 라이브러리(openhtmltopdf → PDFBox 3)는 TrueType 윤곽(`.ttf`) 폰트만 PDF에 넣을 수 있어요. Noto Sans CJK(`.otf`·`.ttc`)는 못 써요. 쓰는 글자만 넣어서(subset) PDF가 커지지 않아요.
- **빠지면**: 앱이 뜨지 않아요(`보고서 PDF 폰트가 없습니다: classpath:fonts/...`). 한글이 `#`으로 찍힌 PDF가 조용히 만들어지는 것보다 낫기 때문이에요.
- **폰트에 없는 글자**: 나눔고딕에 없는 글자(예: `①`·`✓`·이모지·`U+FFFD`)는 PDF에 `#`으로 찍혀요. `→`·`—`는 있어요.
  - 템플릿(`ReportPdfHtml`) 고정 문구는 있는 글자만 써요(`ReportPdfHtmlTest`가 확인).
  - 스냅샷 값(조사역이 고친 본문·LLM 문장 등)은 렌더러가 그리기 전에 cmap을 보고 `□`로 바꾸고, 보고서 번호와 코드포인트를 WARN 로그로 남겨요(`ReportPdfGlyphs`). 저장된 스냅샷은 그대로예요.
