import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.*;
import java.util.*;

public class DumpPCode extends GhidraScript {
    static final String[] HOT_PATTERNS = {
        "calculateRelation", "isA", "resolveTypedef",
        "TypeConstant", "TypeInfo", "getStepTarget"
    };
    
    @Override
    public void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        StringBuilder out = new StringBuilder();
        out.append("{\"functions\":[\n");
        boolean first = true;
        
        for (Function fn : fm.getFunctions(true)) {
            String name = fn.getName();
            if (name == null || !isHot(name)) continue;
            
            if (!first) out.append(",\n");
            first = false;
            
            int paramCount = fn.getParameterCount();
            long invocations = paramCount > 0 ? 5000 : 100;
            String layer = invocations > 10000 ? "CHUNKED" : invocations > 100 ? "MERGED" : "JOURNAL";
            
            out.append("  {\n");
            out.append("    \"name\": \"" + name.replace("\"", "\\\"") + "\",\n");
            out.append("    \"entry\": " + fn.getEntryPoint().getOffset() + ",\n");
            out.append("    \"layer\": \"" + layer + "\",\n");
            out.append("    \"invocations\": " + invocations + ",\n");
            
            Set<String> regs = new HashSet<>();
            out.append("    \"pcode\": [\n");
            boolean firstOp = true;
            
            InstructionIterator it = currentProgram.getListing().getInstructions(fn.getEntryPoint(), true);
            while (it.hasNext()) {
                Instruction insn = it.next();
                if (insn.getAddress().compareTo(fn.getBody().getMaxAddress()) > 0) break;
                
                for (PcodeOp op : insn.getPcode()) {
                    if (!firstOp) out.append(",\n");
                    firstOp = false;
                    
                    out.append("      {");
                    out.append("\"op\":\"" + op.getMnemonic() + "\",");
                    
                    Varnode[] inputs = op.getInputs();
                    out.append("\"inputs\":[");
                    boolean firstIn = true;
                    for (Varnode vn : inputs) {
                        if (!firstIn) out.append(",");
                        firstIn = false;
                        Address addr = vn.getAddress();
                        String space = addr != null && addr.getAddressSpace() != null 
                            ? addr.getAddressSpace().getName() : "unknown";
                        out.append("{\"space\":\"" + space + "\",\"offset\":" + vn.getOffset() + ",\"size\":" + vn.getSize() + "}");
                        if (vn.isRegister()) {
                            Register reg = getRegister(vn);
                            if (reg != null) regs.add(reg.getName());
                        }
                    }
                    out.append("],");
                    
                    Varnode outVn = op.getOutput();
                    if (outVn != null) {
                        Address addr = outVn.getAddress();
                        String space = addr != null && addr.getAddressSpace() != null 
                            ? addr.getAddressSpace().getName() : "unknown";
                        if (outVn.isRegister()) {
                            Register reg = getRegister(outVn);
                            if (reg != null) regs.add(reg.getName());
                        }
                        out.append("\"output\":{\"space\":\"" + space + "\",\"offset\":" + outVn.getOffset() + ",\"size\":" + outVn.getSize() + "}");
                    } else {
                        out.append("\"output\":null");
                    }
                    out.append("}");
                }
            }
            out.append("\n    ],\n");
            out.append("    \"registers\":[");
            boolean firstReg = true;
            for (String r : regs) {
                if (!firstReg) out.append(",");
                firstReg = false;
                out.append("\"" + r + "\"");
            }
            out.append("]\n  }");
        }
        
        out.append("\n]}");
        println(out.toString());
    }
    
    Register getRegister(Varnode vn) {
        try {
            Address addr = vn.getAddress();
            if (addr != null && addr.isRegisterAddress()) {
                return currentProgram.getRegister(addr);
            }
        } catch (Exception e) { }
        return null;
    }
    
    boolean isHot(String name) {
        for (String p : HOT_PATTERNS) if (name.contains(p)) return true;
        return false;
    }
}