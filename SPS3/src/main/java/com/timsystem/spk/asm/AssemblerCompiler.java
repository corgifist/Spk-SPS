package com.timsystem.spk.asm;

import com.timsystem.spk.compiler.lib.IntegerBytesConvert;
import com.timsystem.spk.vm.Bytecode;
import com.timsystem.spk.vm.Instructions;
import com.timsystem.spk.vm.SPKException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Stream;

public class AssemblerCompiler {

    public static int BUILD_SPS = 1;
    public static int BUILD_UNDEFINED = -1;


    public static int PARSE_SEGMENT_ASM = 1;
    public static int PARSE_SEGMENT_DATA = 2;

    public static HashMap<String, Integer> LABELS = new HashMap<>();
    public static int CONSTANTS_POINTER = 0;

    public static Bytecode compileFile(String filename) {
        try {
            return compile(Files.readString(Paths.get(filename)));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Bytecode compile(String assembler) {
        CompilationState state = new CompilationState(PARSE_SEGMENT_ASM, BUILD_UNDEFINED);
        Bytecode bytecode = new Bytecode();
        // Two-pass SPK assembler compilation
        // First pass - compile .data section
        // Second pass - compile all the assembler code
        int lineNumber = 1;
        boolean canCompileData = false;
        for (String line : assembler.split("\n")) {
            line = line.trim();
            if (line.replace("\n", "").equals(".data:")) {
                canCompileData = true;
                continue;
            }
            if (canCompileData) {
                compileData(bytecode, line, lineNumber);
            }
            lineNumber++;
        }

        lineNumber = 1;
        LABELS.clear();
        CONSTANTS_POINTER = 0;
        for (String line : assembler.split("\n")) {
            line = line.trim();
            if (line.replace("\n", "").equals(".data:"))
                break;
            compileInstruction(bytecode, line, state, lineNumber);
            lineNumber++;
        }

        if (state.getBuildState() == BUILD_UNDEFINED) {
            throw new SPKException("AssemblerError", "no build type was specified", -1);
        }
        return bytecode;
    }

    private static void compileData(Bytecode bytecode, String line, int lineNumber) {
        String[] parts = line.split(" ");
        int constantIndex = Integer.parseInt(parts[0]);
        Object expression = compileImmediateExpression(parts, 2, lineNumber);
        int constantsSize = bytecode.getConstants().size();
        if (constantsSize == 0) {
            bytecode.getConstants().add(expression);
            return;
        }
        if (constantIndex > bytecode.getConstants().size()) {
            for (int i = 0; i < constantIndex - constantsSize; i++) {
                bytecode.getConstants().add("If you are seeing this, then you have encountered a bug with .data section! [AssemblerCompiler.java line 81]");
            }
            bytecode.getConstants().add(constantIndex, expression);
        } else if (constantIndex < bytecode.getConstants().size()) {
            bytecode.getConstants().set(constantIndex, expression);
        } else {
            bytecode.getConstants().add(expression);
        }
    }

    private static void compileInstruction(Bytecode bytecode, String instruction, CompilationState state, int lineNumber) {
        String[] parts = instruction.split(" ");
        if (parts[0].equals(".data")) {
            state.setSegmentState(PARSE_SEGMENT_DATA);
            return;
        }
        if (parts[0].equals("#build")) {
            if (parts[1].equals("sps")) {
                state.setBuildState(BUILD_SPS);
            } else {
                throw new SPKException("AssemblerError", "invalid build type specified", lineNumber);
            }
            return;
        }
        // System.out.println(Arrays.toString(parts) + " " + parts[0].charAt(parts[0].length() - 1));
        if (parts[0].charAt(parts[0].length() - 1) == ':') {
            LABELS.put(parts[0].replace(":", ""), bytecode.getBytecode().size());
            return;
        }

        switch (parts[0].toLowerCase()) {
            case "push" -> {
                String inlineIndicator = parts[1].toLowerCase();
                if (inlineIndicator.equals("inline")) {
                    Object expression = compileImmediateExpression(parts, 2, lineNumber); // to skip 'PUSH inline' parts
                    bytecode.getConstants().add(expression);
                    bytecode.writeInstruction(Instructions.OP_PUSH, lineNumber);
                    byte[] indexBytes = IntegerBytesConvert.int2ByteArr(bytecode.getConstants().size() - 1);
                    for (byte b : indexBytes) {
                        bytecode.writeInstruction(b, lineNumber);
                    }
                } else {
                    int index = Integer.parseInt(parts[1]);
                    bytecode.writeInstruction(Instructions.OP_PUSH, lineNumber);
                    byte[] indexBytes = IntegerBytesConvert.int2ByteArr(index);
                    for (byte b : indexBytes) {
                        bytecode.writeInstruction(b, lineNumber);
                    }
                }
            }
            case "dup" -> {
                bytecode.writeInstruction(Instructions.OP_DUP, lineNumber);
            }
            case "pop" -> {
                bytecode.writeInstruction(Instructions.OP_POP, lineNumber);
            }
            case "flip" -> {
                bytecode.writeInstruction(Instructions.OP_FLIP, lineNumber);
            }
            case "swap" -> {
                bytecode.writeInstruction(Instructions.OP_SWAP, lineNumber);
            }
            case "out" -> {
                bytecode.writeInstruction(Instructions.OP_OUT, lineNumber);
            }
            case "inp" -> {
                bytecode.writeInstruction(Instructions.OP_INP, lineNumber);
            }
            case "halt" -> {
                bytecode.writeInstruction(Instructions.OP_HALT, lineNumber);
            }
            case "binary" -> {
                char operation = parts[1].replace("'", "").charAt(0);
                bytecode.writeBinary(operation, lineNumber);
            }
            case "negate" -> {
                bytecode.writeInstruction(Instructions.OP_NEGATE, lineNumber);
            }
            case "create_var" -> {
                String varName = parts[1];
                bytecode.writeInstruction(Instructions.OP_CREATE_VAR, lineNumber);
                bytecode.writeRawConstant(varName, lineNumber);
            }
            case "get_var" -> {
                String varName = parts[1];
                bytecode.writeInstruction(Instructions.OP_GET_VAR, lineNumber);
                bytecode.writeRawConstant(varName, lineNumber);
            }
            case "sign" -> {
                bytecode.writeInstruction(Instructions.OP_SIGN, lineNumber);
            }
            case "chunks" -> throwMissingSpecification(parts, lineNumber);
            case "curch" -> throwMissingSpecification(parts, lineNumber);
            case "chusz" -> throwMissingSpecification(parts, lineNumber);
            case "jmp" -> {
                imitateJump(Instructions.OP_JMP, bytecode, parts, lineNumber);
            }
            case "je" -> {
                imitateJump(Instructions.OP_JE, bytecode, parts, lineNumber);
            }
            case "jne" -> {
                imitateJump(Instructions.OP_JNE, bytecode, parts, lineNumber);
            }
            case "jl" -> {
                imitateJump(Instructions.OP_JL, bytecode, parts, lineNumber);
            }
            case "jg" -> {
                imitateJump(Instructions.OP_JG, bytecode, parts, lineNumber);
            }
            case "jle" -> {
                imitateJump(Instructions.OP_JLE, bytecode, parts, lineNumber);
            }
            case "jge" -> {
                imitateJump(Instructions.OP_JGE, bytecode, parts, lineNumber);
            }
            case "jln" -> {
                imitateJump(Instructions.OP_JLN, bytecode, parts, lineNumber);
            }
            case "jgn" -> {
                imitateJump(Instructions.OP_JGN, bytecode, parts, lineNumber);
            }
            case "jev" -> {
                imitateJump(Instructions.OP_JEV, bytecode, parts, lineNumber);
            }
            case "jue" -> {
                imitateJump(Instructions.OP_JUE, bytecode, parts, lineNumber);
            }
            case "loop" -> {
                imitateLoop(bytecode, parts, lineNumber);
            }
        }
    }

    private static Object compileImmediateExpression(String[] parts, int parseShift, int line) {
        String firstWord = parts[parseShift + 0];
        try {
            return Double.parseDouble(firstWord);
        } catch (NumberFormatException ex) {
            if (firstWord.charAt(0) == '"') {
                return String.join(" ", getSliceOfStream(Arrays.stream(parts), parseShift, parts.length).toList()).replace("\"", "");
            }
        }
        throw new SPKException("AssemblerErorr", "malformed inline expression", line);
    }
    private static void imitateCall(Bytecode bytecode, String[] parts, int line){
        String labelOrAddress = parts[1];
        if (Character.isDigit(labelOrAddress.charAt(0))) {
            // parse raw addressed jump
            bytecode.writeInstruction(Instructions.OP_CALL, line);
            byte[] addressBytes = IntegerBytesConvert.int2ByteArr(Integer.parseInt(labelOrAddress));
            for (byte b : addressBytes) {
                bytecode.writeInstruction(b, line);
            }
        } else {
            bytecode.writeInstruction(Instructions.OP_CALL, line);
            byte[] addressBytes = IntegerBytesConvert.int2ByteArr(LABELS.get(labelOrAddress));
            for (byte b : addressBytes) {
                bytecode.writeInstruction(b, line);
            }
        }
    }
    private static void imitateLoop(Bytecode bytecode, String[] parts, int line){
        String labelOrAddress = parts[1];
        if (Character.isDigit(labelOrAddress.charAt(0))) {
            // parse raw addressed jump
            bytecode.writeInstruction(Instructions.OP_LOOP, line);
            byte[] addressBytes = IntegerBytesConvert.int2ByteArr(Integer.parseInt(labelOrAddress));
            for (byte b : addressBytes) {
                bytecode.writeInstruction(b, line);
            }
        } else {
            bytecode.writeInstruction(Instructions.OP_LOOP, line);
            byte[] addressBytes = IntegerBytesConvert.int2ByteArr(LABELS.get(labelOrAddress));
            for (byte b : addressBytes) {
                bytecode.writeInstruction(b, line);
            }
        }
    }
    private static void imitateJump(byte jmpInstruction, Bytecode bytecode, String[] parts, int line) {
        String labelOrAddress = parts[1];
        if (Character.isDigit(labelOrAddress.charAt(0))) {
            // parse raw addressed jump
            bytecode.writeInstruction(jmpInstruction, line);
            byte[] addressBytes = IntegerBytesConvert.int2ByteArr(Integer.parseInt(labelOrAddress));
            for (byte b : addressBytes) {
                bytecode.writeInstruction(b, line);
            }
        } else {
            bytecode.writeInstruction(jmpInstruction, line);
            byte[] addressBytes = IntegerBytesConvert.int2ByteArr(LABELS.get(labelOrAddress));
            for (byte b : addressBytes) {
                bytecode.writeInstruction(b, line);
            }
        }
    }

    private static void throwMissingSpecification(String[] parts, int line) {
        throw new SPKException("AssemblerError", "Missing compiler specification for '" + parts[0] + "'", line);
    }

    private static <T> Stream<T> getSliceOfStream(Stream<T> stream, int startIndex,
                                                  int endIndex) {
        return stream
                .skip(startIndex)
                .limit(endIndex - startIndex + 1);
    }

}
