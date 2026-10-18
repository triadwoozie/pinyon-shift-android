// Applies the Pinyon Shift reverse-engineering knowledge base to a Ghidra
// program (NEXT_LEVEL_RESEARCH.md NL-2.2). Run as a headless pre-script by
// tools/re/ghidra-workspace.py with one argument: the TSV it writes.
//
// Rows (tab-separated, addresses in hex without 0x):
//   function <addr> <sub_name>              ShiftGlue's function start
//   name     <addr> <name> <confidence>     a recorded function name
//   vtable   <addr> <class>                 a vtable from classes.json
//   note     <addr> <text> <confidence>     an address record (plate comment)
//
// Function starts are disassembled and created before auto-analysis, so the
// analysis follows ShiftGlue's boundaries. Recorded names become the function
// name with the full text and confidence in the plate comment; a name that
// is only a guess keeps the sub_ name and gets the comment.
//@category Pinyon

import java.io.BufferedReader;
import java.io.FileReader;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.DuplicateNameException;

public class ApplyPinyonKnowledge extends GhidraScript {
  private static String symbolName(String text) {
    String s = text.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
    s = s.replaceAll("^_|_$", "");
    if (s.isEmpty()) return "unnamed";
    if (Character.isDigit(s.charAt(0))) s = "_" + s;
    return s.length() > 120 ? s.substring(0, 120) : s;
  }

  private void addPlate(Address at, String text) {
    String old = getPlateComment(at);
    if (old != null && old.contains(text)) return;  // applied by an earlier pass
    setPlateComment(at, old == null || old.isEmpty() ? text : old + "\n" + text);
  }

  // One record can name several functions; the later ones get their address.
  private void nameFunction(Function fn, String name) throws Exception {
    if (fn.getName().equals(name) || fn.getName().startsWith(name + "_")) return;
    try {
      fn.setName(name, SourceType.IMPORTED);
    } catch (DuplicateNameException e) {
      fn.setName(name + "_" + fn.getEntryPoint(), SourceType.IMPORTED);
    }
  }

  @Override
  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) {
      printerr("usage: ApplyPinyonKnowledge <rows.tsv>");
      return;
    }
    int functions = 0, names = 0, vtables = 0, notes = 0, skipped = 0, cleared = 0;
    try (BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
      String line;
      while ((line = in.readLine()) != null) {
        if (monitor.isCancelled()) break;
        String[] f = line.split("\t");
        if (f.length < 3) continue;
        Address at = toAddr(Long.parseLong(f[1], 16));
        if (currentProgram.getMemory().getBlock(at) == null) {
          skipped++;
          continue;
        }
        switch (f[0]) {
          case "function": {
            if (getFunctionAt(at) == null) {
              // The loader or an earlier pass may have typed the bytes as
              // data; ShiftGlue recompiled them as code, so clear that unit.
              Data data = currentProgram.getListing().getDefinedDataContaining(at);
              if (data != null) {
                clearListing(data.getMinAddress(), data.getMaxAddress());
                cleared++;
              }
              disassemble(at);
              Function fn = createFunction(at, f[2]);
              if (fn != null) functions++;
            }
            break;
          }
          case "name": {
            String confidence = f.length > 3 ? f[3] : "guess";
            Function fn = getFunctionAt(at);
            if (fn != null && !confidence.equals("guess"))
              nameFunction(fn, symbolName(f[2]));
            addPlate(at, f[2] + " (" + confidence + ", Pinyon Shift findings)");
            names++;
            break;
          }
          case "vtable": {
            String label = "vtable_" + symbolName(f[2]);
            if (getSymbolAt(at) == null || !getSymbolAt(at).getName().startsWith("vtable_")) {
              try {
                createLabel(at, label, true, SourceType.IMPORTED);
              } catch (DuplicateNameException e) {
                createLabel(at, label + "_" + at, true, SourceType.IMPORTED);
              }
            }
            setEOLComment(at, "vtable of " + f[2] + " (classes.json)");
            vtables++;
            break;
          }
          case "note": {
            String confidence = f.length > 3 ? f[3] : "guess";
            addPlate(at, f[2] + " (" + confidence + ", Pinyon Shift findings)");
            notes++;
            break;
          }
          default:
            break;
        }
      }
    }
    println(String.format("ApplyPinyonKnowledge: %d functions (%d over data cleared), %d names, %d vtables, %d notes, %d outside the image",
        functions, cleared, names, vtables, notes, skipped));
  }
}
