package com.team.aml.source.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** BEGIN~END 블록 하나를 한 사건으로 읽는다. 구조가 어긋나면 넘기지 않고 실패한다. */
public final class PatternFileParser {

    private static final Pattern BEGIN = Pattern.compile("BEGIN LAUNDERING ATTEMPT - ([A-Z-]+)(?::.*)?");
    private static final Pattern END = Pattern.compile("END LAUNDERING ATTEMPT(?: - ([A-Z-]+))?");
    private static final int FIELD_COUNT = 11;

    private PatternFileParser() {}

    public static ParsedPatterns parse(BufferedReader reader) throws IOException {
        return parse(reader, 0);
    }

    /** 사건 번호는 lastEventNumber 다음부터 매겨 이미 적재된 파일과 겹치지 않게 한다. */
    public static ParsedPatterns parse(BufferedReader reader, long lastEventNumber) throws IOException {
        List<PatternRow> rows = new ArrayList<>();
        PatternType openType = null;
        String patternId = null;
        int eventCount = 0;
        int rowsInBlock = 0;
        int lineNumber = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.startsWith("BEGIN")) {
                if (openType != null) {
                    throw new PatternFileFormatException(lineNumber, "END 없이 새 BEGIN이 나왔습니다");
                }
                Matcher begin = BEGIN.matcher(line);
                if (!begin.matches()) {
                    throw new PatternFileFormatException(lineNumber, "BEGIN 형식이 아닙니다");
                }
                int current = lineNumber;
                openType = PatternType.fromLabel(begin.group(1))
                        .orElseThrow(() -> new PatternFileFormatException(current, "알 수 없는 패턴 유형입니다"));
                eventCount++;
                patternId = String.format(Locale.ROOT, "P%06d", lastEventNumber + eventCount);
                rowsInBlock = 0;
            } else if (line.startsWith("END")) {
                if (openType == null) {
                    throw new PatternFileFormatException(lineNumber, "BEGIN 없이 END가 나왔습니다");
                }
                Matcher end = END.matcher(line);
                if (!end.matches()) {
                    throw new PatternFileFormatException(lineNumber, "END 형식이 아닙니다");
                }
                if (end.group(1) != null && PatternType.fromLabel(end.group(1)).orElse(null) != openType) {
                    throw new PatternFileFormatException(lineNumber, "BEGIN과 END의 유형이 다릅니다");
                }
                if (rowsInBlock == 0) {
                    throw new PatternFileFormatException(lineNumber, "거래가 없는 블록입니다");
                }
                openType = null;
            } else if (openType == null) {
                if (!line.isBlank()) {
                    throw new PatternFileFormatException(lineNumber, "블록 밖에 데이터가 있습니다");
                }
            } else {
                if (line.isBlank()) {
                    throw new PatternFileFormatException(lineNumber, "블록 안에 빈 줄이 있습니다");
                }
                rows.add(toRow(line, patternId, openType, lineNumber));
                rowsInBlock++;
            }
        }

        if (openType != null) {
            throw new PatternFileFormatException(lineNumber, "END 없이 파일이 끝났습니다");
        }
        if (eventCount == 0) {
            throw new PatternFileFormatException(lineNumber, "사건이 하나도 없습니다");
        }
        return new ParsedPatterns(eventCount, List.copyOf(rows));
    }

    private static PatternRow toRow(String line, String patternId, PatternType type, int lineNumber) {
        String[] f = line.split(",", -1);
        if (f.length != FIELD_COUNT) {
            throw new PatternFileFormatException(lineNumber, "필드 수가 " + FIELD_COUNT + "개가 아닙니다");
        }
        return new PatternRow(patternId, type, f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10]);
    }
}
