package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.acoustic.AcousticMatchResult;
import com.aidjstudio.ai_dj_studio.dto.acoustic.BpmRelation;
import com.aidjstudio.ai_dj_studio.dto.acoustic.HarmonicRelation;
import com.aidjstudio.ai_dj_studio.dto.track.AcousticFeaturesDto;
import com.aidjstudio.ai_dj_studio.entity.Track;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AcousticMatcherService {

    public static final double DEFAULT_BPM_TOLERANCE_PCT = 8.0;
    private static final Pattern CAMELOT_PATTERN = Pattern.compile("^(1[0-2]|[1-9])([ABab])$");

    public boolean isBpmCompatible(BigDecimal currentBpm, BigDecimal candidateBpm) {
        return isBpmCompatible(currentBpm, candidateBpm, DEFAULT_BPM_TOLERANCE_PCT);
    }

    public boolean isBpmCompatible(BigDecimal currentBpm, BigDecimal candidateBpm, double tolerancePct) {
        return determineBpmRelation(currentBpm, candidateBpm, tolerancePct) != BpmRelation.INCOMPATIBLE;
    }

    public BpmRelation determineBpmRelation(BigDecimal currentBpm, BigDecimal candidateBpm) {
        return determineBpmRelation(currentBpm, candidateBpm, DEFAULT_BPM_TOLERANCE_PCT);
    }

    public BpmRelation determineBpmRelation(BigDecimal currentBpm, BigDecimal candidateBpm, double tolerancePct) {
        validateBpm(currentBpm, "Current");
        validateBpm(candidateBpm, "Candidate");
        if (tolerancePct < 0) {
            throw new IllegalArgumentException("BPM tolerance percentage cannot be negative");
        }

        BigDecimal toleranceRatio = BigDecimal.valueOf(tolerancePct / 100.0);
        BigDecimal oneMinus = BigDecimal.ONE.subtract(toleranceRatio);
        BigDecimal onePlus = BigDecimal.ONE.add(toleranceRatio);

        // 1. Direct match (1.0x): [curr * (1 - tol), curr * (1 + tol)]
        BigDecimal directLower = currentBpm.multiply(oneMinus);
        BigDecimal directUpper = currentBpm.multiply(onePlus);
        if (candidateBpm.compareTo(directLower) >= 0 && candidateBpm.compareTo(directUpper) <= 0) {
            return BpmRelation.DIRECT;
        }

        // 2. Clean half-time multiple (0.5x): [(curr * 0.5) * (1 - tol), (curr * 0.5) * (1 + tol)]
        BigDecimal halfTarget = currentBpm.multiply(BigDecimal.valueOf(0.5));
        BigDecimal halfLower = halfTarget.multiply(oneMinus);
        BigDecimal halfUpper = halfTarget.multiply(onePlus);
        if (candidateBpm.compareTo(halfLower) >= 0 && candidateBpm.compareTo(halfUpper) <= 0) {
            return BpmRelation.HALF_TIME;
        }

        // 3. Clean double-time multiple (2.0x): [(curr * 2.0) * (1 - tol), (curr * 2.0) * (1 + tol)]
        BigDecimal doubleTarget = currentBpm.multiply(BigDecimal.valueOf(2.0));
        BigDecimal doubleLower = doubleTarget.multiply(oneMinus);
        BigDecimal doubleUpper = doubleTarget.multiply(onePlus);
        if (candidateBpm.compareTo(doubleLower) >= 0 && candidateBpm.compareTo(doubleUpper) <= 0) {
            return BpmRelation.DOUBLE_TIME;
        }

        return BpmRelation.INCOMPATIBLE;
    }

    public boolean isCamelotCompatible(String currentKey, String candidateKey) {
        return determineHarmonicRelation(currentKey, candidateKey) != HarmonicRelation.CLASH;
    }

    public HarmonicRelation determineHarmonicRelation(String currentKey, String candidateKey) {
        String normCurrent = validateAndNormalizeCamelotKey(currentKey);
        String normCandidate = validateAndNormalizeCamelotKey(candidateKey);

        Matcher m1 = CAMELOT_PATTERN.matcher(normCurrent);
        Matcher m2 = CAMELOT_PATTERN.matcher(normCandidate);
        m1.matches();
        m2.matches();

        int n1 = Integer.parseInt(m1.group(1));
        String l1 = m1.group(2).toUpperCase();

        int n2 = Integer.parseInt(m2.group(1));
        String l2 = m2.group(2).toUpperCase();

        // Rule 1: Same key (e.g., 8A -> 8A)
        if (n1 == n2 && l1.equals(l2)) {
            return HarmonicRelation.SAME_KEY;
        }

        // Rule 2: Adjacent number on same letter with wraparound (1 and 12)
        if (l1.equals(l2)) {
            int diff1 = (n1 - n2 + 12) % 12;
            int diff2 = (n2 - n1 + 12) % 12;
            if (diff1 == 1 || diff2 == 1) {
                return HarmonicRelation.ADJACENT_KEY;
            }
        }

        // Rule 3: Same number with opposite letter (relative major/minor, e.g., 8A -> 8B)
        if (n1 == n2 && !l1.equals(l2)) {
            return HarmonicRelation.RELATIVE_KEY;
        }

        return HarmonicRelation.CLASH;
    }

    public String validateAndNormalizeCamelotKey(String camelotKey) {
        if (camelotKey == null || camelotKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Camelot key cannot be null or empty");
        }
        String trimmed = camelotKey.trim();
        Matcher matcher = CAMELOT_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Camelot key: " + camelotKey + ". Must be between 1A-12A or 1B-12B");
        }
        int number = Integer.parseInt(matcher.group(1));
        String letter = matcher.group(2).toUpperCase();
        return number + letter;
    }

    public boolean isCompatible(BigDecimal currentBpm, String currentKey, BigDecimal candidateBpm, String candidateKey) {
        return isCompatible(currentBpm, currentKey, candidateBpm, candidateKey, DEFAULT_BPM_TOLERANCE_PCT);
    }

    public boolean isCompatible(BigDecimal currentBpm, String currentKey, BigDecimal candidateBpm, String candidateKey, double tolerancePct) {
        return isBpmCompatible(currentBpm, candidateBpm, tolerancePct) && isCamelotCompatible(currentKey, candidateKey);
    }

    public AcousticMatchResult evaluateMatch(BigDecimal currentBpm, String currentKey, BigDecimal candidateBpm, String candidateKey) {
        return evaluateMatch(currentBpm, currentKey, candidateBpm, candidateKey, DEFAULT_BPM_TOLERANCE_PCT);
    }

    public AcousticMatchResult evaluateMatch(
            BigDecimal currentBpm,
            String currentKey,
            BigDecimal candidateBpm,
            String candidateKey,
            double tolerancePct
    ) {
        validateBpm(currentBpm, "Current");
        validateBpm(candidateBpm, "Candidate");

        String normCurrentKey = validateAndNormalizeCamelotKey(currentKey);
        String normCandidateKey = validateAndNormalizeCamelotKey(candidateKey);

        BpmRelation bpmRelation = determineBpmRelation(currentBpm, candidateBpm, tolerancePct);
        HarmonicRelation harmonicRelation = determineHarmonicRelation(normCurrentKey, normCandidateKey);

        boolean bpmCompatible = bpmRelation != BpmRelation.INCOMPATIBLE;
        boolean camelotCompatible = harmonicRelation != HarmonicRelation.CLASH;
        boolean overallCompatible = bpmCompatible && camelotCompatible;

        BigDecimal bpmDelta = candidateBpm.subtract(currentBpm).setScale(2, RoundingMode.HALF_UP);
        BigDecimal bpmDeltaPercent = candidateBpm.subtract(currentBpm)
                .divide(currentBpm, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        String reason = buildExplanation(
                overallCompatible,
                bpmCompatible,
                camelotCompatible,
                bpmRelation,
                harmonicRelation,
                bpmDeltaPercent,
                normCurrentKey,
                normCandidateKey
        );

        return AcousticMatchResult.builder()
                .compatible(overallCompatible)
                .bpmCompatible(bpmCompatible)
                .camelotCompatible(camelotCompatible)
                .bpmRelation(bpmRelation)
                .harmonicRelation(harmonicRelation)
                .currentBpm(currentBpm)
                .candidateBpm(candidateBpm)
                .bpmDelta(bpmDelta)
                .bpmDeltaPercent(bpmDeltaPercent)
                .currentCamelotKey(normCurrentKey)
                .candidateCamelotKey(normCandidateKey)
                .reason(reason)
                .build();
    }

    public AcousticMatchResult evaluateMatch(Track currentTrack, Track candidateTrack) {
        if (currentTrack == null || candidateTrack == null) {
            throw new IllegalArgumentException("Tracks cannot be null");
        }
        return evaluateMatch(
                currentTrack.getBpm(),
                currentTrack.getCamelotKey(),
                candidateTrack.getBpm(),
                candidateTrack.getCamelotKey()
        );
    }

    public AcousticMatchResult evaluateMatch(AcousticFeaturesDto current, AcousticFeaturesDto candidate) {
        if (current == null || candidate == null) {
            throw new IllegalArgumentException("Acoustic features cannot be null");
        }
        return evaluateMatch(
                current.getBpm(),
                current.getCamelotKey(),
                candidate.getBpm(),
                candidate.getCamelotKey()
        );
    }

    private void validateBpm(BigDecimal bpm, String label) {
        if (bpm == null || bpm.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(label + " BPM must be a positive number");
        }
    }

    private String buildExplanation(
            boolean overallCompatible,
            boolean bpmCompatible,
            boolean camelotCompatible,
            BpmRelation bpmRelation,
            HarmonicRelation harmonicRelation,
            BigDecimal bpmDeltaPercent,
            String currentKey,
            String candidateKey
    ) {
        if (overallCompatible) {
            return String.format(
                    "Compatible segue: BPM is %s (%s%% delta), Key relationship is %s (%s -> %s)",
                    bpmRelation,
                    bpmDeltaPercent,
                    harmonicRelation,
                    currentKey,
                    candidateKey
            );
        }

        StringBuilder sb = new StringBuilder("Incompatible segue: ");
        if (!bpmCompatible) {
            sb.append(String.format("BPM out of tolerance (%s%% delta, %s); ", bpmDeltaPercent, bpmRelation));
        }
        if (!camelotCompatible) {
            sb.append(String.format("Harmonic key clash (%s vs %s, %s)", currentKey, candidateKey, harmonicRelation));
        }
        return sb.toString().trim();
    }
}
