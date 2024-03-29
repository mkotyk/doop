package org.clyze.doop.core

import groovy.transform.CompileStatic
import groovy.transform.InheritConstructors
import groovy.transform.TypeChecked
import groovy.util.logging.Log4j
import org.clyze.analysis.AnalysisOption
import org.clyze.doop.ptatoolkit.scaler.Driver
import org.clyze.doop.utils.DDlog
import org.clyze.doop.utils.SouffleScript
import org.clyze.utils.CPreprocessor
import org.clyze.utils.Executor

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

import static org.apache.commons.io.FileUtils.deleteQuietly
import static org.apache.commons.io.FileUtils.sizeOfDirectory
import static org.apache.commons.io.FilenameUtils.getBaseName

@CompileStatic
@InheritConstructors
@Log4j
@TypeChecked
class SouffleMultiPhaseAnalysis extends SouffleAnalysis {

	@Override
	void run() {
		File preAnalysis = new File(outDir, "context-insensitive.dl")
		deleteQuietly(preAnalysis)
		preAnalysis.createNewFile()
		File analysis = new File(outDir, "${name}.dl")
		deleteQuietly(analysis)
		analysis.createNewFile()

		options.CONFIGURATION.value = "ContextInsensitiveConfiguration"
		def commandsEnv = initExternalCommandsEnvironment(options)

		executor = new Executor(outDir, commandsEnv)
		cpp = new CPreprocessor(this, executor)

		initDatabase(preAnalysis)
		basicAnalysis(preAnalysis)
		if (!options.X_STOP_AT_BASIC.value) {
			log.debug("analysis: ${getBaseName(preAnalysis.name)}")
			mainAnalysis(preAnalysis)
			produceStats(preAnalysis)
		}

		def cacheDir = new File(Doop.souffleAnalysesCache, name)
		cacheDir.mkdirs()
		def script = SouffleScript.newScript(executor, options.VIA_DDLOG.value as Boolean)

		Future<File> compilationFuture = null
		def executorService = Executors.newSingleThreadExecutor()
		boolean provenance = options.SOUFFLE_PROVENANCE.value as boolean
		boolean profiling = options.SOUFFLE_PROFILE.value as boolean
		boolean liveProf = options.SOUFFLE_LIVE_PROFILE.value as boolean
		if (!options.X_STOP_AT_FACTS.value) {
			if (options.VIA_DDLOG.value) {
				// Copy the DDlog converter, needed both for logic
				// compilation and fact post-processing.
				DDlog.copyDDlogConverter(log, outDir)
			}
			compilationFuture = executorService.submit(new Callable<File>() {
				@Override
				File call() {
					log.info "[Task COMPILE...]"
					def generatedFile0 = script.compile(preAnalysis, outDir, cacheDir,
							profiling,
							options.SOUFFLE_DEBUG.value as boolean,
							provenance,
							liveProf,
							options.SOUFFLE_FORCE_RECOMPILE.value as boolean,
							options.X_CONTEXT_REMOVER.value as boolean,
							options.SOUFFLE_USE_FUNCTORS.value as boolean)
					log.info "[Task COMPILE Done]"
					return generatedFile0
				}
			})
		}

		File runtimeMetricsFile = new File(database, "Stats_Runtime.csv")

		def generatedFile0
		if (options.X_SERIALIZE_FACTGEN_COMPILATION.value) {
			generatedFile0 = compilationFuture.get()
			System.gc()
		}

		try {
			log.info "[Task FACTS...]"
			generateFacts()
			script.postprocessFacts(outDir, profiling)
			log.info "[Task FACTS Done]"

			if (options.X_STOP_AT_FACTS.value) return

			if (!options.X_SERIALIZE_FACTGEN_COMPILATION.value) {
			    generatedFile0 = compilationFuture.get()
            }
			script.run(generatedFile0, factsDir, outDir, options.SOUFFLE_JOBS.value as int,
					(options.X_MONITORING_INTERVAL.value as long) * 1000, monitorClosure)

			int dbSize = (sizeOfDirectory(database) / 1024).intValue()
			runtimeMetricsFile.createNewFile()
			runtimeMetricsFile.append("analysis compilation time (sec)\t${script.compilationTime}\n")
			runtimeMetricsFile.append("analysis execution time (sec)\t${script.executionTime}\n")
			runtimeMetricsFile.append("disk footprint (KB)\t$dbSize\n")
			runtimeMetricsFile.append("soot-fact-generation time (sec)\t$factGenTime\n")
		} finally {
			executorService.shutdownNow()
		}
		printStats()
		//Scaler Execution
		Driver scalerMain = new Driver()
		scalerMain.runScaler(factsDir, database)
		//scalerMain.runScaler(factsDir, database)
		options.X_START_AFTER_FACTS.value = factsDir
		options.CONFIGURATION.value = "FullyGuidedContextSensitiveConfiguration"
		options.SCALER_PRE_ANALYSIS.value = null
		executor = new Executor(outDir, commandsEnv)
		cpp = new CPreprocessor(this, executor)

		initDatabase(analysis)
		basicAnalysis(analysis)
		if (!options.X_STOP_AT_BASIC.value) {
			log.debug("analysis: ${getBaseName(analysis.name)}")
			mainAnalysis(analysis)
			produceStats(analysis)
		}

		compilationFuture = null
		executorService = Executors.newSingleThreadExecutor()
		if (!options.X_STOP_AT_FACTS.value) {
			compilationFuture = executorService.submit(new Callable<File>() {
				@Override
				File call() {
					log.info "[Task COMPILE...]"
					def generatedFile = script.compile(analysis, outDir, cacheDir,
							profiling,
							options.SOUFFLE_DEBUG.value as boolean,
							provenance,
							liveProf,
							options.SOUFFLE_FORCE_RECOMPILE.value as boolean,
							options.X_CONTEXT_REMOVER.value as boolean,
							options.SOUFFLE_USE_FUNCTORS.value as boolean)
					log.info "[Task COMPILE Done]"
					return generatedFile
				}
			})
		}

		runtimeMetricsFile = new File(database, "Stats_Runtime.csv")

		def generatedFile
		if (options.X_SERIALIZE_FACTGEN_COMPILATION.value) {
			generatedFile = compilationFuture.get()
			System.gc()
		}
		try {
			if (options.X_STOP_AT_FACTS.value) return

			if (!options.X_SERIALIZE_FACTGEN_COMPILATION.value) {
				generatedFile = compilationFuture.get()
			}
			script.run(generatedFile, factsDir, outDir, options.SOUFFLE_JOBS.value as int,
					   (options.X_MONITORING_INTERVAL.value as long) * 1000, monitorClosure,
					   provenance, liveProf, profiling)

			int dbSize = (sizeOfDirectory(database) / 1024).intValue()
			runtimeMetricsFile.createNewFile()
			runtimeMetricsFile.append("analysis compilation time (sec)\t${script.compilationTime}\n")
			runtimeMetricsFile.append("analysis execution time (sec)\t${script.executionTime}\n")
			runtimeMetricsFile.append("disk footprint (KB)\t$dbSize\n")
			runtimeMetricsFile.append("soot-fact-generation time (sec)\t$factGenTime\n")
		} finally {
			executorService.shutdownNow()
		}
	}

	/**
	 * Initializes the external commands environment of the given analysis, by:
	 * <ul>
	 *     <li>adding the LD_LIBRARY_PATH option to the current environment
	 *     <li>modifying PATH to also include the LD_LIBRARY_PATH option
	 *     <li>adding the LOGICBLOX_HOME option to the current environment
	 *     <li>adding the DOOP_HOME to the current environment
	 *     <li>adding the LB_PAGER_FORCE_START and the LB_MEM_NOWARN to the current environment
	 *     <li>adding the variables/paths/tweaks to meet the lb-env-bin.sh requirements of the pa-datalog distro
	 * </ul>
	 */
	protected static Map<String, String> initExternalCommandsEnvironment(Map<String, AnalysisOption> options) {
		log.debug "Initializing the environment of the external commands"

		Map<String, String> env = [:]
		env.putAll(System.getenv())

		env.LC_ALL = "en_US.UTF-8"

		if (options.LB3.value) {
			def lbHome = options.LOGICBLOX_HOME.value
			env.LOGICBLOX_HOME = lbHome
			//We add these LB specific env vars here to make the server deployment more flexible (and the cli user's life easier)
			env.LB_PAGER_FORCE_START = "true"
			env.LB_MEM_NOWARN = "true"
			env.DOOP_HOME = Doop.doopHome

			//We add the following for pa-datalog to function properly (copied from the lib-env-bin.sh script)
			def path = env.PATH
			env.PATH = "${lbHome}/bin:${path ?: ""}" as String

			def ldLibraryPath = options.LD_LIBRARY_PATH.value
			env.LD_LIBRARY_PATH = "${lbHome}/lib/cpp:${ldLibraryPath ?: ""}" as String
		}

		return env
	}

	void printStats() {
		List<String> lines = []

		File file = new File("${this.outDir}/database/Stats_Runtime.csv")
		file.eachLine { String line -> lines << line.replace("\t", ", ") }

		log.info "-- Runtime metrics --"
		lines.sort()*.split(", ").each {
			printf("%-80s %,d\n", it[0], it[1] as long)
		}

			lines = []

			file = new File("${this.outDir}/database/Stats_Metrics.csv")
			file.eachLine { String line -> lines.add(line.replace("\t", ", ")) }

			log.info "-- Statistics --"
			lines.sort()*.split(", ").each {
				printf("%-80s %,d\n", it[1], it[2] as long)
			}
	}
}
