package org.clyze.doop.util.filter;

import java.io.File;
import java.util.ArrayList;

import static org.clyze.doop.util.TypeUtils.getPackageName;

public class GlobClassFilter implements ClassFilter {
    private final ArrayList<String> packages;
    private final ArrayList<String> prefixes;
    private final ArrayList<String> otherPatterns;
    private final boolean matchEverything;

    public GlobClassFilter(String glob) {
        String patterns[] = glob.split(File.pathSeparator);

        ArrayList<String> packages0 = new ArrayList<>(patterns.length);
        ArrayList<String> prefixes0 = new ArrayList<>(patterns.length);
        ArrayList<String> otherPatterns0 = new ArrayList<>(patterns.length);
        boolean matchEverything0 = false;

        for (String pattern : patterns) {
            if (pattern.endsWith(".*")) {
                String pkg = pattern.substring(0, pattern.length() - 2);
                packages0.add(pkg);
            } else if (pattern.endsWith(".**")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                prefixes0.add(prefix);
            } else if ("**".equals(pattern))
                matchEverything0 = true;
            else
                otherPatterns0.add(pattern);
        }

        // Try to eliminate big data structures (this matters for big
        // programs with long auto-generated regex patterns).
        this.matchEverything = matchEverything0;
        if (matchEverything0) {
            this.packages = null;
            this.prefixes = null;
            this.otherPatterns = null;
        } else {
            // Make repeated matching faster with null checks.
            packages0.trimToSize();
            this.packages = packages0.size() == 0 ? null : packages0;
            prefixes0.trimToSize();
            this.prefixes = prefixes0.size() == 0 ? null : prefixes0;
            otherPatterns0.trimToSize();
            this.otherPatterns = otherPatterns0.size() == 0 ? null : otherPatterns0;
        }
    }

    @Override
    public boolean matches(String className) {
        if (matchEverything)
            return true;

        if (prefixes != null)
            for (String prefix : prefixes)
                if (className.startsWith(prefix))
                    return true;

        // Package name, lazily initialized at most once.
        String pkgName = null;

        if (otherPatterns != null)
            for (String pattern : otherPatterns) {
                if (pattern.equals("*")) {
                    if (pkgName == null)
                        pkgName = getPackageName(className);
                    return pkgName.isEmpty();
                } else if (className.equalsIgnoreCase(pattern))
                    return true;
            }

        if (packages != null) {
            if (pkgName == null)
                pkgName = getPackageName(className);
            for (String pkg : packages) {
                if (pkgName.equalsIgnoreCase(pkg))
                    return true;
            }
	}
        return false;
    }
}
