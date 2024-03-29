package org.clyze.doop.soot;

import java.util.HashSet;
import java.util.Set;
import org.clyze.doop.common.Driver;
import soot.SootClass;
import soot.SootMethod;

class SootDriver extends Driver<SootClass, ThreadFactory> {

    SootDriver(ThreadFactory factory, int totalClasses, Integer cores, boolean ignoreFactGenErrors) {
        super(factory, totalClasses, cores, ignoreFactGenErrors);
    }

    void generateMethod(SootMethod dummyMain, FactWriter writer, boolean reportPhantoms, SootParameters sootParameters) {
        boolean ssa = sootParameters._ssa;
        Set<SootClass> sootClasses = new HashSet<>();
        sootClasses.add(dummyMain.getDeclaringClass());
        FactGenerator factGenerator = new FactGenerator(writer, sootClasses, this, sootParameters);
        factGenerator.generate(dummyMain, new Session());
        writer.writeAndroidEntryPoint(dummyMain);
        factGenerator.run();
    }

    @Override
    protected Runnable getFactGenRunnable() {
        return _factory.newFactGenRunnable(_tmpClassGroup);
    }

    @Override
    protected Runnable getIRGenRunnable() {
        return _factory.newJimpleGenRunnable(_tmpClassGroup);
    }
}
