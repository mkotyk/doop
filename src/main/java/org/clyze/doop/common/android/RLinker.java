package org.clyze.doop.common.android;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.clyze.Constants;
import org.clyze.utils.AARUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A linker of R-class data.
 *
 *  Given a list of AAR files, this linker extracts R.txt from each
 *  file and creates the corresponding R classes that are needed so
 *  that Doop does not report them as phantom classes. This linker does
 *  not mimic the full logic of the aapt tool, it only generates code
 *  that is good enough for linking (in the form of a JAR file
 *  containing all R.java and R*.class files).
 *
 *  This code calls 'javac' and 'jar', so it may fail when these
 *  programs are not in the path.
 */
public class RLinker {

    // A map from package names -> nested class -> field -> element
    // ids. Used by lookupConst() and XML parsing for layout controls.
    private final Map<String, Map<String, Map<String, Integer> > > constants;

    // A map from package names -> nested class -> set of text
    // entries. Used for code generation.
    private final Map<String, Map<String, Set<String> > > rs;

    // Singleton instance.
    private static RLinker instance;

    private RLinker() {
        this.constants = new HashMap<>();
        this.rs = new HashMap<>();
    }

    public static RLinker getInstance() {
        if (instance == null)
            instance = new RLinker();
        return instance;
    }

    Integer lookupConst(String packageName, String nestedName, String fld) {
        Map<String, Map<String, Integer> > pkgEntry = constants.get(packageName);
        if (pkgEntry != null) {
            Map<String, Integer> fieldEntry = pkgEntry.get(nestedName);
            if (fieldEntry != null) {
                Integer c = fieldEntry.get(fld);
                if (c != null)
                    return c;
            }
        }
        return null;
    }

    /**
     * The entry point of the linker. Takes a list of archives
     * (containing paths of AAR files) and a map of AAR paths to
     * package names. Returns the path of the generated JAR (or null
     * if no code generation was done).
     *
     * @param rDir     The directory to place the generated R code. Can
     *                 be null (in which case a temporary location is used).
     *
     * @param tmpDirs  The temporary directory will be added to this set.
     */
    String linkRs(String rDir, Set<String> tmpDirs) {
        if ((rDir == null) || rs.isEmpty()) {
            return null;
        } else {
            final String tmpDir = AARUtils.createTmpDir(tmpDirs);
            rs.forEach ((k, v) -> runProcess("javac " + genR(tmpDir, k, v)));

            // Compile JAR and optionally copy to output directory.
            String tmpJarName = tmpDir + "/" + Constants.R_AUTOGEN_JAR;
            runProcess("jar cf " + tmpJarName + " -C " + tmpDir + " .");
            String outJarName = rDir + "/" + Constants.R_AUTOGEN_JAR;
            try {
                FileUtils.copyFile(new File(tmpJarName), new File(outJarName));
                return outJarName;
            } catch (IOException ex) {
                System.err.println("Failed to copy " + tmpJarName + " to " + outJarName + " : " + ex.getMessage());
            }

            return tmpJarName;
        }
    }

    /**
     * Given an AAR input and a package name, the R constants are read
     * from the R.txt file contained in the archive and the
     * appropriate data structures of the linker are filled in.
     *
     * @param ar   The path of the input.
     * @param pkg  The package name of the input.
     */
    public void readRConstants(String ar, String pkg) {
        if (!ar.endsWith(".aar"))
            return;
        try {
            String rText = getZipEntry(new ZipFile(ar), "R.txt");
            if (rText != null) {
                for (String line : rText.split("\n|\r"))
                    if (line.length() != 0)
                        processRLine(ar, line, pkg);
            }
        } catch (IOException ex) {
            System.err.println("Error while reading R.txt: " + ar);
            System.err.println(ex.getMessage());
        }
    }

    /**
     * Process each line in R.txt and (a) generate Java code for later
     * use (in 'rs') and (b) remember constant ids (in 'constants').
     *
     * @param ar    The path of the archive.
     * @param line  The line of text to be processed.
     * @param pkg   The package name of the archive.
     */
    private void processRLine(String ar, String line, String pkg) {
        final String delim = " ";
        String[] parts = line.split(delim);
        if (parts.length < 2) {
            System.err.println("Error processing R.txt line: " + line);
        } else if (pkg == null) {
            System.err.println("WARNING: no package: " + ar);
        } else {

            // Extract information from the line text.
            String nestedR = parts[1];
            // String rName = pkg + "." + "R$" + nestedR;
            String[] newParts = new String[parts.length];
            newParts[0] = parts[0];
            newParts[1] = parts[2];
            newParts[2] = "=";
            System.arraycopy(parts, 3, newParts, 3, parts.length - 3);

            // Remember int constants.
            if (newParts[0].equals("int") && (newParts.length > 3)) {
                String num = newParts[3];
                int val = num.startsWith("0x") ?
                    (int)(Long.parseLong(num.substring(2), 16)) :
                    Integer.parseInt(num);
                addConstant(pkg, nestedR, newParts[1], val);
            }

            // Generate Java code.
            Map<String, Set<String>> pkgEntry = rs.getOrDefault(pkg, new HashMap<>());
            Set<String> set = pkgEntry.getOrDefault(nestedR, new HashSet<>());
            set.add("        public static " + String.join(delim, newParts) + ";");
            pkgEntry.put(nestedR, set);
            rs.put(pkg, pkgEntry);
        }
    }

    /**
     * Adds a tuple (packageName, nested, f, c) to 'constants'.
     */
    private void addConstant(String packageName, String nested, String f, int c) {
        Map<String, Map<String, Integer>> packageEntry = constants.getOrDefault(packageName, new HashMap<>());
        Map<String, Integer> nestedEntry = packageEntry.getOrDefault(nested, new HashMap<>());
        Integer val = nestedEntry.get(f);
        if (val == null)
            nestedEntry.put(f, c);
        else if (!val.equals(c))
            System.err.println("WARNING: duplicate values for " + nested + "$" + f + ": " + val + ", " + c);
        packageEntry.put(nested, nestedEntry);
        constants.put(packageName, packageEntry);
    }

    private static void runProcess(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
            int exitVal = p.exitValue();
            if (exitVal != 0) {
                System.out.println(cmd + " exit value = " + exitVal);
            }
        } catch (Exception ex) {
            System.err.println("Error invoking: " + cmd);
            ex.printStackTrace();
        }
    }

    private static String genR(String tmpDir, String pkg,
                               Map<String, Set<String>> rData) {
        String subdir = tmpDir + File.separator + pkg.replaceAll("\\.", File.separator);
        if (new File(subdir).mkdirs())
            System.out.println("Created directory: " + subdir);
        String rFile = subdir + "/R.java";
        System.out.println("Generating " + rFile);
        Collection<String> lines = new ArrayList<>();
        lines.add("// Auto-generated R.java by Doop.\n");
        lines.add("package " + pkg + ";\n");
        lines.add("public final class R {");
        rData.forEach ((k, v) -> genNestedR(k, v, lines));
        lines.add("}");

        try {
            Files.write(Paths.get(rFile), lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("Error generating R class for package: " + pkg);
            ex.printStackTrace();
            return null;
        }
        return rFile;
    }

    private static void genNestedR(String nestedName, Collection<String> data,
                                   Collection<String> lines) {
        lines.add("    public static final class " + nestedName + " {\n");
        lines.addAll(data);
        lines.add("    }\n");
    }

    private static String getZipEntry(ZipFile zip, String entryName) {
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.getName().equals(entryName)) {
                    InputStream is = zip.getInputStream(e);
                    return IOUtils.toString(is, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException ex) {
            System.err.println("Error reading " + entryName + " from " + zip.getName());
            System.err.println(ex.getMessage());
        }
        return null;
    }

}
