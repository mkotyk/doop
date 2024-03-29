package org.clyze.doop.wala;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.dalvik.classLoader.DexIRFactory;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.types.annotations.Annotation;
import org.clyze.doop.common.BasicJavaSupport;
import org.clyze.doop.common.Database;
import org.clyze.doop.common.DoopErrorCodeException;
import org.clyze.doop.common.Parameters;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class WalaInvoker {

    private static boolean isApplicationClass(Parameters walaParameters, IClass klass) {
        // Change package delimiter from "/" to "."
        return walaParameters.isApplicationClass(WalaUtils.fixTypeString(klass.getName().toString()));
    }

    public void main(String[] args) throws IOException, DoopErrorCodeException {
        if (args.length == 0) {
            System.err.println("usage: [options] file...");
            throw new DoopErrorCodeException(0);
        }
        Parameters walaParameters = new Parameters();
        walaParameters.initFromArgs(args);
        run(walaParameters);
    }

    private void run(Parameters walaParameters) throws IOException, DoopErrorCodeException {
        StringBuilder classPath = new StringBuilder();
        List<String> inputs = walaParameters.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (i == 0)
                classPath.append(inputs.get(i));
            else
                classPath.append(":").append(inputs.get(i));
        }

//        for (int i = 0; i < walaParameters.getLibraries().size(); i++) {
//            classPath.append(":").append(walaParameters.getLibraries().get(i));
//        }

        System.out.println("WALA classpath: " + classPath);
        for (String lib : walaParameters.getPlatformLibs())
            System.out.println("Platform Library: " + lib);

        for (String lib : walaParameters.getDependencies())
            System.out.println("Application Library: " + lib);

        AnalysisScope scope;
        if(walaParameters._android)
            scope = WalaScopeReader.setUpAndroidAnalysisScope(walaParameters.getInputs(), "", walaParameters.getPlatformLibs(), walaParameters.getDependencies());
        else
            scope = WalaScopeReader.setupJavaAnalysisScope(walaParameters.getInputs(),"", walaParameters.getPlatformLibs(), walaParameters.getDependencies());

        ClassHierarchy cha = null;
        try {
            cha = ClassHierarchyFactory.make(scope);
        } catch (ClassHierarchyException e) {
            e.printStackTrace();
        }

        assert cha != null;
        Iterator<IClass> classes = cha.iterator();
        String outputDir = walaParameters.getOutputDir();

        try (Database db = new Database(new File(outputDir))) {
            WalaRepresentation rep = new WalaRepresentation();
            WalaFactWriter walaFactWriter = new WalaFactWriter(db, walaParameters._extractMoreStrings, walaParameters._writeArtifactsMap, rep);
            WalaThreadFactory walaThreadFactory = new WalaThreadFactory(walaFactWriter, outputDir, walaParameters._android);

            BasicJavaSupport java = new BasicJavaSupport(walaParameters);

            if (walaParameters._android) {
                WalaAndroidXMLParser parser = new WalaAndroidXMLParser(walaParameters, walaFactWriter, java);
                parser.process();
            }
            System.out.println("Number of classes: " + cha.getNumberOfClasses());

            IAnalysisCacheView cache;
            if (walaParameters._android)
                cache = new AnalysisCacheImpl(new DexIRFactory());
            else
                cache = new AnalysisCacheImpl();

            java.preprocessInputs();
            walaFactWriter.writePreliminaryFacts(java, walaParameters);
            db.flush();

            IClass klass;
            Collection<IClass> classesSet = new HashSet<>();
            Map<String, List<String>> signaturePolymorphicMethods = new HashMap<>();
            while (classes.hasNext()) {
                klass = classes.next();
                if (isApplicationClass(walaParameters, klass)) {
                    walaFactWriter.writeApplicationClass(klass);
                }
                classesSet.add(klass);
                for (IMethod m : klass.getDeclaredMethods()) {
                    addIfSignaturePolymorphic(m, signaturePolymorphicMethods);
                    //System.out.println(m.toString());
                    try {
                        cache.getIR(m);
                    } catch (Throwable e) {
                        System.out.println("Error while creating IR for method: " + m.getReference() + "\n" + e);
                        e.printStackTrace();
                    }
                }
            }
            walaFactWriter.setSignaturePolyMorphicMethods(signaturePolymorphicMethods);

            WalaDriver driver = new WalaDriver(walaThreadFactory, cha.getNumberOfClasses(), walaParameters._cores, cache, false);
            driver.generateInParallel(classesSet);

            if (walaFactWriter.getNumberOfPhantomTypes() > 0)
                System.out.println("WARNING: Input contains phantom types. \nNumber of phantom types: " + walaFactWriter.getNumberOfPhantomTypes());
            if (walaFactWriter.getNumberOfPhantomMethods() > 0)
                System.out.println("WARNING: Input contains phantom methods. \nNumber of phantom methods: " + walaFactWriter.getNumberOfPhantomMethods());
            if (walaFactWriter.getNumberOfPhantomBasedMethods() > 0)
                System.out.println("WARNING: Input contains phantom based methods. \nNumber of phantom based methods: " + walaFactWriter.getNumberOfPhantomBasedMethods());

            walaFactWriter.writeLastFacts(java);

            db.flush();
        }
    }

    private void addIfSignaturePolymorphic(IMethod m, Map <String, List<String>> signaturePolymorphics)
    {
        Collection<Annotation> annotations = m.getAnnotations();
        String className = WalaUtils.fixTypeString(m.getDeclaringClass().getName().toString());
        for(Annotation ann: annotations)
        {
            if(ann.getType().getName().toString().equals("Ljava/lang/invoke/MethodHandle$PolymorphicSignature"))
            {
                List<String> declaredExceptions = new ArrayList<>();
                try{
                    TypeReference[] exceptions = m.getDeclaredExceptions();
                    if(exceptions != null && exceptions.length > 0) {
                        for(TypeReference exc: exceptions)
                            declaredExceptions.add(WalaUtils.fixTypeString(exc.toString()));
                    }
                } catch (InvalidClassFileException e) {
                    e.printStackTrace();
                }
                signaturePolymorphics.put(className + ":" + m.getName().toString(), declaredExceptions);
            }
        }
    }

}
