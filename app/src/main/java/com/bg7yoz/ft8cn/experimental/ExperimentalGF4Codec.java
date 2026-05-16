package com.bg7yoz.ft8cn.experimental;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Python GF(4) QC-LDPC 原型的直接 Java 移植版本。
 *
 * 保持代码简洁易读，因为目前的目标是在 FT8CN 内部进行调制解调器验证，
 * 而不是追求极致的解码速度。
 */
public final class ExperimentalGF4Codec {
    private static final int[][] GF4_ADD_TABLE = new int[][]{
            {0, 1, 2, 3},
            {1, 0, 3, 2},
            {2, 3, 0, 1},
            {3, 2, 1, 0},
    };
    private static final int[][] GF4_MULTIPLY_TABLE = new int[][]{
            {0, 0, 0, 0},
            {0, 1, 2, 3},
            {0, 2, 3, 1},
            {0, 3, 1, 2},
    };

    private static final int CIRCULANT_SIZE = 8;
    private static final int INFO_BLOCK_COUNT = 2;
    private static final int PARITY_BLOCK_COUNT = 6;
    private static final int MAX_ITERATIONS = 18;

    private static final Entry[][] INFO_BASE_MATRIX = new Entry[][]{
            {new Entry(1, 0), new Entry(2, 1)},
            {new Entry(2, 2), new Entry(3, 0)},
            {new Entry(3, 4), new Entry(1, 3)},
            {new Entry(1, 5), new Entry(3, 6)},
            {new Entry(2, 7), new Entry(1, 2)},
            {new Entry(3, 1), new Entry(2, 5)},
    };

    private final RowEntry[][] parityCheck;
    private final int[][] variableNeighbors;

    public ExperimentalGF4Codec() {
        parityCheck = buildParityCheck();
        variableNeighbors = buildVariableNeighbors(parityCheck);
    }

    public int encodedSymbolCount(int infoBitCount) {
        int infoSymbolCount = (infoBitCount + 1) / 2;
        int blockCount = Math.max(1, (infoSymbolCount + getInfoSymbolsPerBlock() - 1) / getInfoSymbolsPerBlock());
        return blockCount * getCodewordSymbolsPerBlock();
    }

    public int[] encodeBits(int[] bits) {
        int[] infoSymbols = bitsToGf4Symbols(bits);
        ArrayList<Integer> encoded = new ArrayList<>();

        for (int start = 0; start < Math.max(infoSymbols.length, 1); start += getInfoSymbolsPerBlock()) {
            int[] block = new int[getInfoSymbolsPerBlock()];
            int copyLength = Math.min(getInfoSymbolsPerBlock(), Math.max(0, infoSymbols.length - start));
            if (copyLength > 0) {
                System.arraycopy(infoSymbols, start, block, 0, copyLength);
            }
            int[] encodedBlock = encodeSymbolBlock(block);
            for (int value : encodedBlock) {
                encoded.add(value);
            }
            if (infoSymbols.length == 0) {
                break;
            }
        }

        int[] encodedSymbols = new int[encoded.size()];
        for (int i = 0; i < encoded.size(); i++) {
            encodedSymbols[i] = encoded.get(i);
        }
        return gf4SymbolsToBits(encodedSymbols);
    }

    public int[] decodeSymbolLogLikelihoods(double[][] symbolLogLikelihoods, int infoBitCount) {
        int infoSymbolCount = (infoBitCount + 1) / 2;
        int blockCount = Math.max(1, (infoSymbolCount + getInfoSymbolsPerBlock() - 1) / getInfoSymbolsPerBlock());
        int expectedSymbols = blockCount * getCodewordSymbolsPerBlock();
        if (symbolLogLikelihoods.length < expectedSymbols) {
            throw new IllegalArgumentException("GF4 解码所需的编码符号不足");
        }

        ArrayList<Integer> decodedInfoSymbols = new ArrayList<>();
        for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
            int start = blockIndex * getCodewordSymbolsPerBlock();
            double[][] channelLogs = normalizeRows(copyRows(symbolLogLikelihoods, start, getCodewordSymbolsPerBlock()));

            MessageMatrix qMessages = new MessageMatrix(parityCheck.length, getCodewordSymbolsPerBlock());
            MessageMatrix rMessages = new MessageMatrix(parityCheck.length, getCodewordSymbolsPerBlock());
            for (int rowIndex = 0; rowIndex < parityCheck.length; rowIndex++) {
                for (RowEntry rowEntry : parityCheck[rowIndex]) {
                    qMessages.set(rowIndex, rowEntry.column, channelLogs[rowEntry.column].clone());
                    rMessages.set(rowIndex, rowEntry.column, new double[4]);
                }
            }

            double[][] posterior = copyRows(channelLogs, 0, channelLogs.length);
            int[] decision = hardDecision(posterior);
            if (!checkSatisfied(decision)) {
                for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                    for (int rowIndex = 0; rowIndex < parityCheck.length; rowIndex++) {
                        for (RowEntry rowEntry : parityCheck[rowIndex]) {
                            rMessages.set(
                                    rowIndex,
                                    rowEntry.column,
                                    checkToVariableMessage(rowIndex, rowEntry.column, qMessages)
                            );
                        }
                    }

                    for (int column = 0; column < getCodewordSymbolsPerBlock(); column++) {
                        if (variableNeighbors[column].length == 0) {
                            continue;
                        }

                        double[] total = channelLogs[column].clone();
                        for (int rowIndex : variableNeighbors[column]) {
                            addInPlace(total, rMessages.get(rowIndex, column));
                        }
                        posterior[column] = normalizeRow(total);

                        for (int rowIndex : variableNeighbors[column]) {
                            double[] extrinsic = total.clone();
                            subtractInPlace(extrinsic, rMessages.get(rowIndex, column));
                            qMessages.set(rowIndex, column, normalizeRow(extrinsic));
                        }
                    }

                    decision = hardDecision(posterior);
                    if (checkSatisfied(decision)) {
                        break;
                    }
                }
            }

            for (int i = 0; i < getInfoSymbolsPerBlock(); i++) {
                decodedInfoSymbols.add(decision[i]);
            }
        }

        int[] symbols = new int[Math.min(infoSymbolCount, decodedInfoSymbols.size())];
        for (int i = 0; i < symbols.length; i++) {
            symbols[i] = decodedInfoSymbols.get(i);
        }
        int[] bits = gf4SymbolsToBits(symbols);
        return Arrays.copyOf(bits, infoBitCount);
    }

    private int getInfoSymbolsPerBlock() {
        return INFO_BLOCK_COUNT * CIRCULANT_SIZE;
    }

    private int getCodewordSymbolsPerBlock() {
        return (INFO_BLOCK_COUNT + PARITY_BLOCK_COUNT) * CIRCULANT_SIZE;
    }

    private int[] encodeSymbolBlock(int[] infoSymbols) {
        int[][] infoBlocks = new int[INFO_BLOCK_COUNT][CIRCULANT_SIZE];
        for (int blockIndex = 0; blockIndex < INFO_BLOCK_COUNT; blockIndex++) {
            System.arraycopy(infoSymbols, blockIndex * CIRCULANT_SIZE, infoBlocks[blockIndex], 0, CIRCULANT_SIZE);
        }

        int[][] parityBlocks = new int[PARITY_BLOCK_COUNT][CIRCULANT_SIZE];
        for (int rowIndex = 0; rowIndex < INFO_BASE_MATRIX.length; rowIndex++) {
            int[] accumulator = new int[CIRCULANT_SIZE];
            for (int blockIndex = 0; blockIndex < INFO_BASE_MATRIX[rowIndex].length; blockIndex++) {
                Entry entry = INFO_BASE_MATRIX[rowIndex][blockIndex];
                if (entry == null) {
                    continue;
                }
                int[] rotated = rotate(infoBlocks[blockIndex], entry.shift);
                int[] scaled = scale(rotated, entry.coefficient);
                for (int i = 0; i < CIRCULANT_SIZE; i++) {
                    accumulator[i] = gf4Add(accumulator[i], scaled[i]);
                }
            }
            parityBlocks[rowIndex] = accumulator;
        }

        int[] output = new int[getCodewordSymbolsPerBlock()];
        for (int blockIndex = 0; blockIndex < INFO_BLOCK_COUNT; blockIndex++) {
            System.arraycopy(infoBlocks[blockIndex], 0, output, blockIndex * CIRCULANT_SIZE, CIRCULANT_SIZE);
        }
        for (int blockIndex = 0; blockIndex < PARITY_BLOCK_COUNT; blockIndex++) {
            int offset = getInfoSymbolsPerBlock() + blockIndex * CIRCULANT_SIZE;
            System.arraycopy(parityBlocks[blockIndex], 0, output, offset, CIRCULANT_SIZE);
        }
        return output;
    }

    private RowEntry[][] buildParityCheck() {
        ArrayList<RowEntry[]> rows = new ArrayList<>();
        for (int rowBlock = 0; rowBlock < INFO_BASE_MATRIX.length; rowBlock++) {
            for (int localRow = 0; localRow < CIRCULANT_SIZE; localRow++) {
                ArrayList<RowEntry> entries = new ArrayList<>();
                for (int colBlock = 0; colBlock < INFO_BASE_MATRIX[rowBlock].length; colBlock++) {
                    Entry entry = INFO_BASE_MATRIX[rowBlock][colBlock];
                    if (entry == null) {
                        continue;
                    }
                    int localColumn = (localRow + entry.shift) % CIRCULANT_SIZE;
                    int column = colBlock * CIRCULANT_SIZE + localColumn;
                    entries.add(new RowEntry(column, entry.coefficient));
                }

                int parityColumn = getInfoSymbolsPerBlock() + rowBlock * CIRCULANT_SIZE + localRow;
                entries.add(new RowEntry(parityColumn, 1));
                rows.add(entries.toArray(new RowEntry[0]));
            }
        }
        return rows.toArray(new RowEntry[0][]);
    }

    private int[][] buildVariableNeighbors(RowEntry[][] rows) {
        ArrayList<ArrayList<Integer>> neighbors = new ArrayList<>();
        for (int column = 0; column < getCodewordSymbolsPerBlock(); column++) {
            neighbors.add(new ArrayList<>());
        }
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            for (RowEntry rowEntry : rows[rowIndex]) {
                neighbors.get(rowEntry.column).add(rowIndex);
            }
        }

        int[][] output = new int[neighbors.size()][];
        for (int i = 0; i < neighbors.size(); i++) {
            output[i] = neighbors.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        return output;
    }

    private boolean checkSatisfied(int[] decision) {
        for (RowEntry[] row : parityCheck) {
            int accumulator = 0;
            for (RowEntry rowEntry : row) {
                accumulator = gf4Add(accumulator, gf4Multiply(rowEntry.coefficient, decision[rowEntry.column]));
            }
            if (accumulator != 0) {
                return false;
            }
        }
        return true;
    }

    private double[] checkToVariableMessage(int rowIndex, int targetColumn, MessageMatrix qMessages) {
        RowEntry[] row = parityCheck[rowIndex];
        int targetCoefficient = 1;
        ArrayList<RowEntry> others = new ArrayList<>();
        for (RowEntry rowEntry : row) {
            if (rowEntry.column == targetColumn) {
                targetCoefficient = rowEntry.coefficient;
            } else {
                others.add(rowEntry);
            }
        }

        double[] summedDistribution = new double[]{0.0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (RowEntry other : others) {
            double[] scaled = scaleSymbolMessage(qMessages.get(rowIndex, other.column), other.coefficient);
            summedDistribution = logConvolveGf4(summedDistribution, scaled);
        }

        double[] output = new double[4];
        for (int targetSymbol = 0; targetSymbol < 4; targetSymbol++) {
            int syndromeSymbol = GF4_MULTIPLY_TABLE[targetCoefficient][targetSymbol];
            output[targetSymbol] = summedDistribution[syndromeSymbol];
        }
        return normalizeRow(output);
    }

    private static double[] logConvolveGf4(double[] left, double[] right) {
        double[] output = new double[]{
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY
        };
        for (int leftSymbol = 0; leftSymbol < 4; leftSymbol++) {
            if (!Double.isFinite(left[leftSymbol])) {
                continue;
            }
            for (int rightSymbol = 0; rightSymbol < 4; rightSymbol++) {
                if (!Double.isFinite(right[rightSymbol])) {
                    continue;
                }
                int summedSymbol = GF4_ADD_TABLE[leftSymbol][rightSymbol];
                output[summedSymbol] = logAddExp(output[summedSymbol], left[leftSymbol] + right[rightSymbol]);
            }
        }
        return output;
    }

    private static double[] scaleSymbolMessage(double[] message, int coefficient) {
        if (coefficient == 1) {
            return message.clone();
        }

        double[] scaled = new double[]{
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY
        };
        for (int sourceSymbol = 0; sourceSymbol < 4; sourceSymbol++) {
            int targetSymbol = GF4_MULTIPLY_TABLE[coefficient][sourceSymbol];
            scaled[targetSymbol] = message[sourceSymbol];
        }
        return scaled;
    }

    private static int[] hardDecision(double[][] rows) {
        int[] result = new int[rows.length];
        for (int row = 0; row < rows.length; row++) {
            int bestIndex = 0;
            double bestValue = rows[row][0];
            for (int column = 1; column < 4; column++) {
                if (rows[row][column] > bestValue) {
                    bestValue = rows[row][column];
                    bestIndex = column;
                }
            }
            result[row] = bestIndex;
        }
        return result;
    }

    public static double[][] normalizeRows(double[][] rows) {
        double[][] normalized = new double[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            normalized[i] = normalizeRow(rows[i]);
        }
        return normalized;
    }

    public static double[] normalizeRow(double[] row) {
        double maxValue = row[0];
        for (int i = 1; i < row.length; i++) {
            if (row[i] > maxValue) {
                maxValue = row[i];
            }
        }

        double[] normalized = new double[row.length];
        for (int i = 0; i < row.length; i++) {
            normalized[i] = row[i] - maxValue;
        }
        return normalized;
    }

    public static int[] bitsToGf4Symbols(int[] bits) {
        if ((bits.length & 1) != 0) {
            throw new IllegalArgumentException("GF(4) 映射需要偶数个比特");
        }
        int[] symbols = new int[bits.length / 2];
        for (int i = 0; i < symbols.length; i++) {
            symbols[i] = ((bits[i * 2] & 1) << 1) | (bits[i * 2 + 1] & 1);
        }
        return symbols;
    }

    public static int[] gf4SymbolsToBits(int[] symbols) {
        int[] bits = new int[symbols.length * 2];
        int cursor = 0;
        for (int symbol : symbols) {
            bits[cursor++] = (symbol >> 1) & 1;
            bits[cursor++] = symbol & 1;
        }
        return bits;
    }

    public static int gf4Add(int left, int right) {
        return GF4_ADD_TABLE[left & 0x03][right & 0x03];
    }

    public static int gf4Multiply(int left, int right) {
        return GF4_MULTIPLY_TABLE[left & 0x03][right & 0x03];
    }

    public static double logAddExp(double left, double right) {
        if (!Double.isFinite(left)) {
            return right;
        }
        if (!Double.isFinite(right)) {
            return left;
        }
        double max = Math.max(left, right);
        return max + Math.log(Math.exp(left - max) + Math.exp(right - max));
    }

    private static int[] rotate(int[] values, int shift) {
        int[] output = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            output[i] = values[(i + shift) % values.length];
        }
        return output;
    }

    private static int[] scale(int[] values, int coefficient) {
        if (coefficient == 1) {
            return values.clone();
        }
        int[] output = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            output[i] = gf4Multiply(coefficient, values[i]);
        }
        return output;
    }

    private static double[][] copyRows(double[][] source, int start, int count) {
        double[][] output = new double[count][4];
        for (int i = 0; i < count; i++) {
            output[i] = source[start + i].clone();
        }
        return output;
    }

    private static void addInPlace(double[] target, double[] delta) {
        for (int i = 0; i < target.length; i++) {
            target[i] += delta[i];
        }
    }

    private static void subtractInPlace(double[] target, double[] delta) {
        for (int i = 0; i < target.length; i++) {
            target[i] -= delta[i];
        }
    }

    private static final class Entry {
        public final int coefficient;
        public final int shift;

        private Entry(int coefficient, int shift) {
            this.coefficient = coefficient;
            this.shift = shift;
        }
    }

    private static final class RowEntry {
        public final int column;
        public final int coefficient;

        private RowEntry(int column, int coefficient) {
            this.column = column;
            this.coefficient = coefficient;
        }
    }

    private static final class MessageMatrix {
        private final double[][][] values;
        private final boolean[][] valid;

        private MessageMatrix(int rowCount, int columnCount) {
            values = new double[rowCount][columnCount][];
            valid = new boolean[rowCount][columnCount];
        }

        public void set(int row, int column, double[] value) {
            values[row][column] = value;
            valid[row][column] = true;
        }

        public double[] get(int row, int column) {
            if (!valid[row][column]) {
                throw new IllegalStateException("消息在初始化前被查找");
            }
            return values[row][column];
        }
    }
}

