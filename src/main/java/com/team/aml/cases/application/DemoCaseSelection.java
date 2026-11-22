package com.team.aml.cases.application;

import com.team.aml.detection.domain.AlertForCaseOpening;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Private, fixed-date case selection verified before the first write. */
final class DemoCaseSelection {
    private static final String MODEL = "xgb-hi-medium-r04-event-c2-31-v1";
    private static final String FEATURES = "hi-medium-evidence31-96h-r04-event-v1";
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0000001");

    private DemoCaseSelection() {}

    static List<AlertForCaseOpening> select(String path, String sha256, LocalDate date,
            List<AlertForCaseOpening> scored) {
        byte[] bytes;
        try (var stream = Files.newInputStream(Path.of(path))) {
            bytes = stream.readNBytes(8193);
        } catch (IOException e) {
            throw new IllegalStateException("Demo case selection could not be read", e);
        }
        if (bytes.length > 8192 || !digest(bytes).equalsIgnoreCase(sha256)) {
            throw new IllegalStateException("Demo case selection SHA-256 mismatch");
        }
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.size() < 2 || lines.size() > 31 || !date.toString().equals(lines.get(0))) {
            throw new IllegalStateException("Demo case selection date or count mismatch");
        }
        var byId = new HashMap<Long, AlertForCaseOpening>();
        for (var alert : scored) {
            if (byId.put(alert.alertId(), alert) != null) {
                throw new IllegalStateException("Duplicate scored alert ID");
            }
        }
        var selected = new ArrayList<AlertForCaseOpening>();
        var ids = new HashSet<Long>();
        var numbers = new HashSet<String>();
        for (String line : lines.subList(1, lines.size())) {
            var columns = line.split("\t", -1);
            if (columns.length != 3 || columns[0].isBlank()) {
                throw new IllegalStateException("Invalid demo case selection row");
            }
            long id = Long.parseLong(columns[1]);
            BigDecimal expected = new BigDecimal(columns[2]);
            if (id <= 0 || !ids.add(id) || !numbers.add(columns[0]) ||
                    expected.signum() < 0 || expected.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException("Duplicate or invalid demo case selection");
            }
            var alert = byId.get(id);
            if (alert == null) {
                throw new IllegalStateException("A selected alert is not SCORED for this date");
            }
            if (!alert.alertNo().equals(columns[0]) || !MODEL.equals(alert.modelVersion()) ||
                    !FEATURES.equals(alert.featureVersion()) ||
                    alert.score().subtract(expected).abs().compareTo(TOLERANCE) > 0) {
                throw new IllegalStateException("Selected alert or rc3 score differs from the frozen manifest");
            }
            selected.add(alert);
        }
        return selected;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
