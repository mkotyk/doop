package org.clyze.doop.core

import org.clyze.analysis.*
import org.clyze.doop.common.Parameters

import static DoopAnalysis.INFORMATION_FLOW_SUFFIX
import static org.apache.commons.io.FilenameUtils.getExtension
import static org.apache.commons.io.FilenameUtils.removeExtension

@Singleton
class DoopAnalysisFamily implements AnalysisFamily {

	private static final String DEFAULT_JAVA_PLATFORM = "java_8"
	private static final String GROUP_ENGINE = "Datalog engine"
	private static final String GROUP_FACTS = "Fact generation"
	private static final String GROUP_HEAPDL = "HeapDL"
	private static final String GROUP_INFORMATION_FLOW = "Information flow"
	private static final String GROUP_NATIVE = "Native code"
	private static final String GROUP_PYTHON = "Python"
	private static final String GROUP_REFLECTION = "Reflection"
	private static final String GROUP_STATS = "Statistics"

	private static final int SERVER_DEFAULT_THRESHOLD = 1000

	@Override
	String getName() { "doop" }

	@Override
	void init() {}

	@Override
	List<AnalysisOption> supportedOptions() { SUPPORTED_OPTIONS }

	@Override
	Map<String, AnalysisOption> supportedOptionsAsMap() { SUPPORTED_OPTIONS.collectEntries { [(it.id): it] } }

	AnalysisOption getOptionByName(String n) {
		SUPPORTED_OPTIONS.find { it.name == n }
	}

	private static List<AnalysisOption> SUPPORTED_OPTIONS = [
			/* Start Main options */
			new AnalysisOption<String>(
					id: "USER_SUPPLIED_ID",
					name: "id",
					description: "The analysis id. If omitted, it is automatically generated.",
					argName: "ID"
			),
			new AnalysisOption<String>(
					id: "ANALYSIS",
					name: "analysis",
					optName: "a",
					argName: "NAME",
					description: "The name of the analysis.",
					validValues: analysesSouffle() + ["----- (LB analyses) -----"] + analysesLB(),
					isMandatory: true
			),
			new AnalysisOption<File>(
					id: "OUT_DIR",
					cli: false
			),
			new AnalysisOption<File>(
					id: "CACHE_DIR",
					cli: false
			),
			new IntegerAnalysisOption(
					id: "TIMEOUT",
					name: "timeout",
					optName: "t",
					argName: "MINUTES",
					description: "The analysis max allocated execution time. Measured in minutes.",
					value: 90, // Minutes
					cli: false
			),
			new AnalysisOption<List<String>>(
					id: "INPUTS",
					name: "input-file",
					optName: "i",
					description: "The (application) input files of the analysis. Accepted formats: .jar, .apk, .aar",
					value: [],
					multipleValues: true,
					argName: "INPUT",
					argInputType: InputType.INPUT,
					isMandatory: true
			),
			new AnalysisOption<List<String>>(
					id: "LIBRARIES",
					name: "library-file",
					optName: "l",
					description: "The dependency/library files of the application. Accepted formats: .jar, .apk, .aar",
					value: [],
					multipleValues: true,
					argName: "LIBRARY",
					argInputType: InputType.LIBRARY,
					isMandatory: false
			),
			new AnalysisOption<List<String>>(
					id: "PLATFORMS",
					name: "platform-files",
					multipleValues: true,
					argInputType: InputType.LIBRARY,
					cli: false
			),
			new AnalysisOption<List<String>>(
					id: "HEAPDLS",
					name: "heapdl-file",
					group: GROUP_HEAPDL,
					description: "Use dynamic information from memory dump, using HeapDL. Takes one or more files (`.hprof` format or stack traces).",
					value: [],
					multipleValues: true,
					argName: "HEAPDLS",
					argInputType: InputType.HEAPDL,
					forCacheID: true,
					forPreprocessor: true,
			),
			new AnalysisOption<String>(
					id: "PLATFORM",
					name: "platform",
					argName: "PLATFORM",
					description: "The platform on which to perform the analysis. For Android, the plaftorm suffix can either be 'stubs' (provided by the Android SDK), 'fulljars' (a custom Android build), or 'apks' (custom Dalvik equivalent). Default: ${DEFAULT_JAVA_PLATFORM}.",
					value: DEFAULT_JAVA_PLATFORM,
					validValues: DoopAnalysisFactory.availablePlatforms,
					forCacheID: true,
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "PLATFORMS_LIB",
					name: "platforms-lib",
					description: "The path to the platform libs directory.",
					value: System.getenv("DOOP_PLATFORMS_LIB"),
					cli: false
			),
			new BooleanAnalysisOption(
					id: "SKIP_CODE_FACTGEN",
					name: "skip-code-factgen",
					group: GROUP_FACTS,
					description: "Don't generate facts for code."
			),

			new AnalysisOption<List<String>>(
					id: "MAIN_CLASS",
					name: "main",
					argName: "MAIN",
					description: "Specify the main class(es) separated by spaces.",
					value: [],
					multipleValues: true,
					forCacheID: true
			),
			new AnalysisOption<String>(
					id: "CONFIGURATION",
					name: "configuration",
					description: "Analysis Configuration",
					value: "ContextInsensitiveConfiguration",
					cli: false,
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "IMPORT_PARTITIONS",
					name: "import-partitions",
					argName: "FILE",
					description: "Specify the partitions.",
					argInputType: InputType.MISC,
					forCacheID: true,
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "TAMIFLEX",
					name: "tamiflex",
					group: GROUP_REFLECTION,
					description: "Use file with tamiflex data for reflection.",
					argName: "FILE",
					argInputType: InputType.MISC,
					forCacheID: true,
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "KEEP_SPEC",
					name: "keep-spec",
					argName: "FILE",
					argInputType: InputType.MISC,
					forCacheID: true,
					description: "Give a 'keep' specification."
			),
			new AnalysisOption<String>(
					id: "SPECIAL_CONTEXT_SENSITIVITY_METHODS",
					name: "special-cs-methods",
					argName: "FILE",
					argInputType: InputType.MISC,
					description: "Use a file that specifies special context sensitivity for some methods.",
					forPreprocessor: true,	
					forCacheID: true
			),
			new AnalysisOption<String>(
					id: "USER_DEFINED_PARTITIONS",
					name: "user-defined-partitions",
					argName: "FILE",
					argInputType: InputType.MISC,
					description: "Use a file that specifies the partitions of the analyzed program.",
					forPreprocessor: true,
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "SANITY",
					name: "sanity",
					description: "Load additional logic for sanity checks.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "NO_STANDARD_EXPORTS",
					name: "no-standard-exports",
					description: "Do not export standard relations.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "CACHE",
					name: "cache",
					group: GROUP_FACTS,
					description: "The analysis will use the cached facts, if they exist."
			),
			new BooleanAnalysisOption(
					id: "SEPARATE_EXCEPTION_OBJECTS",
					name: "disable-merge-exceptions",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "NO_SSA",
					name: "no-ssa",
					group: GROUP_FACTS,
					description: "Disable the default policy of using SSA transformation on input.",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "SSA",
					name: "ssa",
					group: GROUP_FACTS,
					value: true,
					forCacheID: true,
					cli: false
			),
			new BooleanAnalysisOption(
					id: "RUN_JPHANTOM",
					name: "run-jphantom",
					description: "Run jphantom for non-existent referenced jars.",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "RUN_FLOWDROID",
					name: "run-flowdroid",
					description: "Run FlowDroid to generate dummy main method.",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "DONT_REPORT_PHANTOMS",
					name: "dont-report-phantoms",
					group: GROUP_FACTS,
					description: "Do not report phantom methods/types during fact generation.",
					value: false
			),
			new BooleanAnalysisOption(
					id: "GENERATE_JIMPLE",
					name: "generate-jimple",
					group: GROUP_FACTS,
					description: "Generate Jimple/Shimple files along with .facts files.",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "GENERATE_ARTIFACTS_MAP",
					name: "generate-artifacts-map",
					group: GROUP_FACTS,
					description: "Generate artifacts map.",
					value: false,
					cli: false,
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "SIMULATE_NATIVE_RETURNS",
					name: "simulate-native-returns",
					group: GROUP_NATIVE,
					description: "Assume native methods return mock objects.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "SCAN_NATIVE_CODE",
					name: "scan-native-code",
					group: GROUP_NATIVE,
					description: "Scan native code for specific patterns.",
					forCacheID: true,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DACAPO",
					name: "dacapo",
					description: "Load additional logic for DaCapo (2006) benchmarks properties.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DACAPO_BACH",
					name: "dacapo-bach",
					description: "Load additional logic for DaCapo (Bach) benchmarks properties.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "WALA_FACT_GEN",
					name: "wala-fact-gen",
					group: GROUP_FACTS,
					description: "Use WALA to generate the facts.",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "DEX_FACT_GEN",
					name: "dex",
					group: GROUP_FACTS,
					description: "Use custom front-end to generate facts for .apk inputs, using Soot for other inputs.",
					forCacheID: true
			),
			new AnalysisOption<String>(
					id: "DECODE_APK",
					name: "decode-apk",
					group: GROUP_FACTS,
					description: "Decode .apk inputs to facts directory.",
					value: false,
					cli: false,
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "PYTHON",
					name: "python",
					forCacheID: true,
					cli: false
			),
			new IntegerAnalysisOption(
					id: "FACT_GEN_CORES",
					name: "fact-gen-cores",
					group: GROUP_FACTS,
					description: "Number of cores to use for parallel fact generation.",
					argName: "NUMBER"
			),
			new AnalysisOption<String>(
					id: "APP_REGEX",
					name: "regex",
					argName: "EXPRESSION",
					description: "A regex expression for the Java package names of the analyzed application.",
					forCacheID: true
			),
			new AnalysisOption<String>(
					id: "AUTO_APP_REGEX_MODE",
					name: "auto-app-regex-mode",
					argName: "MODE",
					description: "When no app regex is given, either compute an app regex for the first input ('first') or for all inputs ('all').",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "ANDROID",
					name: "android",
					description: "If true the analysis enables Android-specific logic.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "LEGACY_ANDROID_PROCESSING",
					name: "legacy-android-processing",
					description: "If true the analysis uses the legacy processor for Android resources."
			),
			new BooleanAnalysisOption(
					id: "LEGACY_SOOT_INVOCATION",
					name: "legacy-soot-invocation",
					description: "If true, Soot will be invoked using a custom classloader (may use less memory, only supported on Java < 9)."
			),
			new BooleanAnalysisOption(
					id: "CFG_ANALYSIS",
					name: "cfg",
					description: "Perform a CFG analysis.",
					cli: true
			),
			/* End Main options */

			/* Start Scaler related options */
			new BooleanAnalysisOption(
					id: "SCALER_PRE_ANALYSIS",
					name: "scaler-pre",
					description: "Enable the analysis to be the pre-analysis of Scaler, and outputs the information required by Scaler.",
					forPreprocessor: true
			),
			/* End Scaler related options */

			/* Start Zipper related options */
			new BooleanAnalysisOption(
					id: "ZIPPER_PRE_ANALYSIS",
					name: "zipper-pre",
					description: "Enable the analysis to be the pre-analysis of Zipper, and outputs the information required by Zipper.",
					forPreprocessor: true
			),
			new AnalysisOption(
					id: "ZIPPER",
					name: "zipper",
					description: "Use file with precision-critical methods selected by Zipper, these methods are analyzed context-sensitively.",
					argName: "FILE",
					argInputType: InputType.MISC,
					forCacheID: true,
					forPreprocessor: true
			),
			/* End Zipper related options */

			/* Start Python related options */
			new BooleanAnalysisOption(
					id: "SINGLE_FILE_ANALYSIS",
					name: "single-file-analysis",
					group: GROUP_PYTHON,
					description: "Flag to be passed to WALAs IR translator to produce IR that makes the analysis of a single script file easier.",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "TENSOR_SHAPE_ANALYSIS",
					name: "tensor-shape-analysis",
					group: GROUP_PYTHON,
					description: "Enable tensor shape analysis for Python.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "FULL_TENSOR_PRECISION",
					name: "full-tensor-precision",
					group: GROUP_PYTHON,
					description: "Full precision tensor shape analysis(not guaranteed to finish).",
					forPreprocessor: true
			),
			/* End Python related options */

			/* Start preprocessor normal flags */
			new BooleanAnalysisOption(
					id: "NO_MERGES",
					name: "no-merges",
					description: "No merges for string constants.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS",
					name: "distinguish-reflection-only-string-constants",
					group: GROUP_REFLECTION,
					description: "Merge all string constants except those useful for reflection.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DISTINGUISH_ALL_STRING_CONSTANTS",
					name: "distinguish-all-string-constants",
					description: "Treat string constants as regular objects.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DISTINGUISH_ALL_STRING_BUFFERS",
					name: "distinguish-all-string-buffers",
					description: "Avoids merging string buffer objects (not recommended).",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DISTINGUISH_STRING_BUFFERS_PER_PACKAGE",
					name: "distinguish-string-buffers-per-package",
					group: GROUP_REFLECTION,
					description: "Merges string buffer objects only on a per-package basis (default behavior for reflection-classic).",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "EXCLUDE_IMPLICITLY_REACHABLE_CODE",
					name: "exclude-implicitly-reachable-code",
					description: "Don't make any method implicitly reachable.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "COARSE_GRAINED_ALLOCATION",
					name: "coarse-grained-allocation-sites",
					description: "Aggressively merge allocation sites for all regular object types, in lib and app alike.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "NO_MERGE_LIBRARY_OBJECTS",
					name: "no-merge-library-objects",
					description: "Disable the default policy of merging library (non-collection) objects of the same type per-method."
			),
			new BooleanAnalysisOption(
					id: "MERGE_LIBRARY_OBJECTS_PER_METHOD",
					value: true,
					forPreprocessor: true,
					cli: false
			),
			new BooleanAnalysisOption(
					id: "CONTEXT_SENSITIVE_LIBRARY_ANALYSIS",
					name: "cs-library",
					description: "Enable context-sensitive analysis for internal library objects.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION",
					name: "reflection",
					group: GROUP_REFLECTION,
					description: "Enable logic for handling Java reflection.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_CLASSIC",
					name: "reflection-classic",
					group: GROUP_REFLECTION,
					description: "Enable (classic subset of) logic for handling Java reflection."
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_SUBSTRING_ANALYSIS",
					name: "reflection-substring-analysis",
					group: GROUP_REFLECTION,
					description: "Allows reasoning on what substrings may yield reflection objects.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_CONTEXT_SENSITIVITY",
					name: "reflection-context-sensitivity",
					group: GROUP_REFLECTION,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_HIGH_SOUNDNESS_MODE",
					name: "reflection-high-soundness-mode",
					group: GROUP_REFLECTION,
					description: "Enable extra rules for more sound handling of reflection.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_SPECULATIVE_USE_BASED_ANALYSIS",
					name: "reflection-speculative-use-based-analysis",
					group: GROUP_REFLECTION,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_INVENT_UNKNOWN_OBJECTS",
					name: "reflection-invent-unknown-objects",
					group: GROUP_REFLECTION,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "GROUP_REFLECTION_STRINGS",
					name: "reflection-coloring",
					group: GROUP_REFLECTION,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "EXTRACT_MORE_STRINGS",
					name: "extract-more-strings",
					group: GROUP_FACTS,
					description: "Extract more string constants from the input code (may degrade analysis performance).",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_METHOD_HANDLES",
					name: "reflection-method-handles",
					group: GROUP_REFLECTION,
					description: "Reflection-based handling of the method handle APIs.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_REFINED_OBJECTS",
					name: "reflection-refined-objects",
					group: GROUP_REFLECTION,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "REFLECTION_DYNAMIC_PROXIES",
					name: "reflection-dynamic-proxies",
					group: GROUP_REFLECTION,
					description: "Enable handling of the Java dynamic proxy API.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "LIGHT_REFLECTION_GLUE",
					name: "light-reflection-glue",
					group: GROUP_REFLECTION,
					description: "Handle some shallow reflection patterns without full reflection support.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "GENERATE_OPTIMIZATION_DIRECTIVES",
					name: "gen-opt-directives",
					description: "Generates additional relations for code optimization uses.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DISCOVER_TESTS",
					name: "discover-tests",
					description: "Discover and treat test code (e.g. JUnit) as entry points.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "DISCOVER_MAIN_METHODS",
					name: "discover-main-methods",
					description: "Discover main() methods.",
					forPreprocessor: true
			),
			/* End preprocessor normal flags */
			/* Start Souffle related options */
			new IntegerAnalysisOption(
					id: "SOUFFLE_JOBS",
					name: "souffle-jobs",
					group: GROUP_ENGINE,
					description: "Specify number of Souffle jobs to run.",
					argName: "NUMBER",
					value: 4
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_DEBUG",
					name: "souffle-debug",
					group: GROUP_ENGINE,
					description: "Enable profiling in the Souffle binary."
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_PROFILE",
					name: "souffle-profile",
					group: GROUP_ENGINE,
					description: "Enable profiling in the Souffle binary."
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_LIVE_PROFILE",
					name: "souffle-live-profile",
					group: GROUP_ENGINE,
					description: "Enable live profiling in the Souffle binary."
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_PROVENANCE",
					name: "souffle-provenance",
					group: GROUP_ENGINE,
					description: "Call the provenance browser."
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_FORCE_RECOMPILE",
					name: "souffle-force-recompile",
					group: GROUP_ENGINE,
					description: "Force recompilation of Souffle logic."
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_RUN_INTERPRETED",
					name: "souffle-run-interpreted",
					group: GROUP_ENGINE,
					description: "Run souffle in interpreted mode (currently only for Python analyses)."
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_USE_FUNCTORS",
					name: "souffle-use-functors",
					group: GROUP_ENGINE,
					description: "Enale the use of user defined functors in Souffle."
			),
			new BooleanAnalysisOption(
					id: "SOUFFLE_INCREMENTAL_OUTPUT",
					name: "souffle-incremental-output",
					group: GROUP_ENGINE,
					description: "Use the functor for incremental output in Souffle."
			),
			new BooleanAnalysisOption(
					id: "VIA_DDLOG",
					name: "via-ddlog",
					group: GROUP_ENGINE,
					description: "Convert and run Souffle with DDlog.",
					forPreprocessor: true
			),
			/* End Souffle related options */

			//Information-flow, etc.
			new AnalysisOption<String>(
					id: "DISABLE_POINTS_TO",
					name: "disable-points-to",
					description: "Disable (most) points-to analysis reasoning. This should only be combined with analyses that compensate (e.g., types-only).",
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "FEATHERWEIGHT_ANALYSIS",
					name: "featherweight-analysis",
					description: "Perform a featherweight analysis (global state and complex objects immutable).",
					forPreprocessor: true
			),
            new AnalysisOption<String>(
                    id: "CONSTANT_FOLDING",
                    name: "constant-folding",
                    description: "Constant folding logic"
            ),
			new AnalysisOption<String>(
					id: "SYMBOLIC_REASONING",
					name: "symbolic-reasoning",
					description: "Symbolic reasoning for expressions."
			),
			new AnalysisOption<String>(
					id: "INFORMATION_FLOW",
					name: "information-flow",
					group: GROUP_INFORMATION_FLOW,
					argName: "APPLICATION_PLATFORM",
					description: "Load additional logic to perform information flow analysis.",
					validValues: informationFlowPlatforms(Doop.addonsPath, Doop.souffleAddonsPath),
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "INFORMATION_FLOW_HIGH_SOUNDNESS",
					name: "information-flow-high-soundness",
					group: GROUP_INFORMATION_FLOW,
					description: "Enter high soundness mode for information flow microbenchmarks.",
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "INFORMATION_FLOW_EXTRA_CONTROLS",
					name: "information-flow-extra-controls",
					group: GROUP_INFORMATION_FLOW,
					argName: "CONTROLS",
					description: "Load additional sensitive layout control from string triplets \"id1,type1,parent_id1,...\".",
					forCacheID: true,
					forPreprocessor: true
			),
			new AnalysisOption(
					id: "OPEN_PROGRAMS",
					name: "open-programs",
					argName: "STRATEGY",
					description: "Create analysis entry points and environment using various strategies (such as 'servlets-only' or 'concrete-types').",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "OPEN_PROGRAMS_IMMUTABLE_CTX",
					name: "open-programs-context-insensitive-entrypoints",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "OPEN_PROGRAMS_IMMUTABLE_HCTX",
					name: "open-programs-heap-context-insensitive-entrypoints",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "IGNORE_MAIN_METHOD",
					name: "ignore-main-method",
					description: "If main class is not given explicitly, do not try to discover it from jar/filename info. Open-program analysis variant will be triggered in this case.",
					forPreprocessor: true
			),

			new AnalysisOption<String>(
					id: "HEAPDL_NOSTRINGS",
					name: "heapdl-nostrings",
					group: GROUP_HEAPDL,
					forCacheID: true,
					description: "Do not model string values uniquely in a memory dump.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "HEAPDL_DYNAMICVARPOINTSTO",
					name: "heapdl-dvpt",
					group: GROUP_HEAPDL,
					forCacheID: true,
					description: "Import dynamic var-points-to information.",
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "IMPORT_DYNAMIC_FACTS",
					name: "import-dynamic-facts",
					group: GROUP_HEAPDL,
					argName: "FACTS_FILE",
					argInputType: InputType.MISC,
					description: "Use dynamic information from file.",
					forPreprocessor: true
			),

			/* Start LogicBlox related options */
			new AnalysisOption<String>(
					id: "LOGICBLOX_HOME",
					group: GROUP_ENGINE,
					value: System.getenv("LOGICBLOX_HOME"),
					cli: false
			),
			new AnalysisOption<String>(
					id: "LD_LIBRARY_PATH", //the value is set based on LOGICBLOX_HOME
					group: GROUP_ENGINE,
					cli: false
			),
			new AnalysisOption<String>(
					id: "BLOXBATCH", //the value is set based on LOGICBLOX_HOME
					group: GROUP_ENGINE,
					cli: false
			),
			new AnalysisOption<String>(
					id: "BLOX_OPTS",
					group: GROUP_ENGINE,
					cli: false
			),
			new BooleanAnalysisOption(
					id: "LB3",
					name: "lb",
					group: GROUP_ENGINE,
					description: "Use the LB engine."
			),
			/* End LogicBlox related options */

			/* Start non-standard flags */
			new BooleanAnalysisOption(
					id: "X_STATS_FULL",
					name: "Xstats-full",
					group: GROUP_STATS,
					description: "Load additional logic for collecting statistics.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "X_STATS_NONE",
					name: "Xstats-none",
					group: GROUP_STATS,
					description: "Do not load logic for collecting statistics.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "X_STATS_DEFAULT",
					name: "Xstats-default",
					group: GROUP_STATS,
					description: "Load default logic for collecting statistics.",
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "X_STATS_AROUND",
					name: "Xstats-around",
					group: GROUP_STATS,
					description: "Load custom logic for collecting statistics.",
					argName: "FILE",
					argInputType: InputType.MISC
			),
			new AnalysisOption<String>(
					id: "X_STOP_AT_FACTS",
					name: "Xstop-at-facts",
					group: GROUP_FACTS,
					description: "Only generate facts and exit. Link result to OUT_DIR",
					argName: "OUT_DIR",
					argInputType: InputType.MISC
			),
			new AnalysisOption<String>(
					id: "X_STOP_AT_BASIC",
					name: "Xstop-at-basic",
					group: GROUP_FACTS,
					description: "Run the basic analysis and exit. Possible strategies: default, classes-scc (outputs the classes in SCC), partitioning (outputs the classes in partitions)",
					argName: "PARTITIONING_STRATEGY"
			),
			new BooleanAnalysisOption(
					id: "X_DRY_RUN",
					name: "Xdry-run",
					description: "Do a dry run of the analysis."
			),
			new BooleanAnalysisOption(
					id: "X_SERVER_LOGIC",
					name: "Xserver-logic",
					description: "Run server queries under addons/server-logic.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "X_SERVER_CHA",
					name: "Xserver-cha",
					description: "Run server queries related to CHA."
			),
			new BooleanAnalysisOption(
					id: "X_EXTRA_METRICS",
					name: "Xextra-metrics",
					description: "Run extra metrics logic under addons/statistics.",
					forPreprocessor: false
			),
			new BooleanAnalysisOption(
					id: "X_ORACULAR_HEURISTICS",
					name: "Xoracular-heuristics",
					description: "Run sensitivity heuristics logic under addons/oracular.",
					forPreprocessor: false
			),
			new BooleanAnalysisOption(
					id: "X_CONTEXT_DEPENDENCY_HEURISTIC",
					name: "Xcontext-dependency-heuristic",
					description: "Run context dependency heuristics logic under addons/oracular.",
					forPreprocessor: false
			),
			new AnalysisOption<String>(
					id: "X_EXTRA_LOGIC",
					name: "Xextra-logic",
					description: "Include file with extra rules.",
					argName: "FILE",
					argInputType: InputType.MISC,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "X_CONTEXT_REMOVER",
					name: "Xcontext-remover",
					description: "Run the context remover for reduced memory use (only available in context-insensitive analysis).",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "X_SYMLINK_CACHED_FACTS",
					name: "Xsymlink-cached-facts",
					group: GROUP_FACTS,
					description: "Use symbolic links instead of copying cached facts.",
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "X_LOW_MEM",
					name: "Xlow-mem",
					group: GROUP_FACTS,
					description: "Use less memory. Does not support all options."
			),
			new BooleanAnalysisOption(
					id: "X_SERIALIZE_FACTGEN_COMPILATION",
					name: "Xserialize-factgen-compilation",
					group: GROUP_FACTS,
					description: "Do not run fact generation and compilation in parallel.",
					cli: false
			),
			new AnalysisOption<String>(
					id: "X_START_AFTER_FACTS",
					name: "Xstart-after-facts",
					group: GROUP_FACTS,
					description: "Import facts from OUT_DIR and start the analysis. Application/library inputs are ignored.",
					argName: "OUT_DIR",
					argInputType: InputType.MISC,
					forPreprocessor: true
			),
			new IntegerAnalysisOption(
					id: "X_SERVER_LOGIC_THRESHOLD",
					name: "Xserver-logic-threshold",
					argName: "THRESHOLD",
					description: "Threshold when reporting points-to information in server logic (per points-to set). default: ${SERVER_DEFAULT_THRESHOLD}",
					value: SERVER_DEFAULT_THRESHOLD,
					forPreprocessor: true
			),
			new AnalysisOption<String>(
					id: "X_R_OUT_DIR",
					name: "XR-out-dir",
					description: "When linking AAR inputs, place generated R code in R_OUT_DIR",
					argName: "R_OUT_DIR",
					argInputType: InputType.MISC
			),
			new BooleanAnalysisOption(
					id: "X_IGNORE_WRONG_STATICNESS",
					name: "Xignore-wrong-staticness",
					group: GROUP_FACTS,
					description: "Ignore 'wrong static-ness' errors in Soot.",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "X_IGNORE_FACTGEN_ERRORS",
					name: "Xignore-factgen-errors",
					group: GROUP_FACTS,
					description: "Continue with analysis despite fact generation errors.",
					forCacheID: true
			),
			new AnalysisOption<List<String>>(
					id: "ALSO_RESOLVE",
					name: "also-resolve",
					group: GROUP_FACTS,
					description: "Force resolution of class(es) by Soot.",
					value: [],
					multipleValues: true,
					argName: "CLASS",
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "THOROUGH_FACT_GEN",
					name: "thorough-fact-gen",
					group: GROUP_FACTS,
					description: "Attempt to resolve as many classes during fact generation (may take more time).",
					forCacheID: true
			),
			new IntegerAnalysisOption(
					id: "X_MONITORING_INTERVAL",
					name: "Xmonitoring-interval",
					argName: "INTERVAL",
					description: "Monitoring interval for sampling memory and cpu usage. default: 5sec",
					value: 5
			),
			new AnalysisOption<String>(
					id: "X_FACTS_SUBSET",
					name: "Xfacts-subset",
					group: GROUP_FACTS,
					description: "Produce facts only for a subset of the given classes.",
					argName: "SUBSET",
					validValues: Parameters.FactsSubSet.values().collect { it as String },
					forCacheID: true
			),
			new BooleanAnalysisOption(
					id: "X_UNIQUE_FACTS",
					name: "Xunique-facts",
					group: GROUP_FACTS,
					description: "Eliminate redundancy from .facts files.",
					forCacheID: true
			),
			new AnalysisOption<String>(
					id: "X_EXTEND_FACTS",
					name: "Xextend-facts",
					group: GROUP_FACTS,
					description: "Expand upon the facts found in the given directory.",
					argName: "DIR",
					argInputType: InputType.MISC
			),
			/* End non-standard flags */

			/* TODO: deprecated or broken? */
			new AnalysisOption<String>(
					id: "MUST",
					name: "must",
					description: "Run the must-alias analysis.",
					cli: false
			),
			new BooleanAnalysisOption(
					id: "MUST_AFTER_MAY",
					cli: false,
					forPreprocessor: true
			),
			new BooleanAnalysisOption(
					id: "TRANSFORM_INPUT",
					name: "transform-input",
					description: "Transform input by removing redundant instructions.",
					forPreprocessor: true,
					cli: false
			),
	]

	private static List<String> analysesFor(String path, String fileToLookFor) {
		if (!path) {
			println "Error: Doop was not initialized correctly, could not read analyses names."
			return []
		}
		def analyses = []
		new File(path).eachDir { File dir ->
			def f = new File(dir, fileToLookFor)
			if (f.exists() && f.file) analyses << dir.name
		}
		analyses.sort()
	}

	private static List<String> analysesSouffle() {
		try {
			if (!Doop.souffleAnalysesPath) {
				Doop.initDoopFromEnv()
			}
		} catch (e) {
			println "Error initializing Doop: Souffle logic path not found, set DOOP_HOME."
		}
		analysesFor(Doop.souffleAnalysesPath, "analysis.dl")
	}

	private static List<String> analysesLB() {
		try {
			if (!Doop.analysesPath) {
				Doop.initDoopFromEnv()
			}
		} catch (e) {
			println "Error initializing Doop: LB logic path not found, set DOOP_HOME."
		}
		analysesFor(Doop.analysesPath, "analysis.logic")
	}

	private static List<String> informationFlowPlatforms(String lbDir, String souffleDir) {
		List<String> platforms_LB = []
		List<String> platforms_Souffle = []
		Closure scan = { ifDir ->
			if (ifDir) {
				new File("${ifDir}/information-flow")?.eachFile { File f ->
					String n = f.name
					String base = removeExtension(n)
					int platformEndIdx = base.lastIndexOf(INFORMATION_FLOW_SUFFIX)
					if (platformEndIdx != -1) {
						String ext = getExtension(n)
						if (ext == "logic") {
							platforms_LB << base.substring(0, platformEndIdx)
						} else if (ext == "dl") {
							platforms_Souffle << base.substring(0, platformEndIdx)
						}
					}
				}
			}
		}

		scan(lbDir)
		scan(souffleDir)

		List<String> platforms =
				(platforms_Souffle.collect {
					it + ((it in platforms_LB) ? "" : " (Souffle-only)")
				}) +
						(platforms_LB.findAll { !(it in platforms_Souffle) }
								.collect { it + " (LB-only)" })
		return platforms.sort()
	}

    static Collection<File> getAllInputs(Map<String, AnalysisOption> options) {
        Collection<File> inputs = [] as List
        inputs += options.INPUTS.value
        inputs += options.LIBRARIES.value
        inputs += options.HEAPDLS.value
        inputs += options.PLATFORMS.value
        return inputs
    }
}
