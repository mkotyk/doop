package org.clyze.doop.core

import groovy.util.logging.Log4j
import java.util.jar.Attributes
import java.util.jar.JarFile
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.clyze.analysis.*
import org.clyze.doop.common.DoopErrorCodeException
import org.clyze.doop.input.DefaultInputResolutionContext
import org.clyze.doop.input.InputResolutionContext
import org.clyze.doop.input.PlatformManager
import org.clyze.doop.utils.PackageUtil
import org.clyze.utils.CheckSum
import org.clyze.utils.FileOps

/**
 * A Factory for creating Analysis objects.
 *
 * [Note] All the methods invoked by newAnalysis (either directly or
 * indirectly) could have been static helpers (e.g. entailed in the
 * Helper class) but they are protected instance methods to allow
 * descendants to customize all possible aspects of Analysis creation.
 */
@Log4j
class DoopAnalysisFactory implements AnalysisFactory<DoopAnalysis> {

	static final char[] EXTRA_ID_CHARACTERS = '_-+.'.toCharArray()
	static final String HASH_ALGO = "SHA-256"
	static final availableConfigurations = [
			"dependency-analysis"                : "TwoObjectSensitivePlusHeapConfiguration",
			"types-only"                         : "TypesOnlyConfiguration",
			"context-insensitive"                : "ContextInsensitiveConfiguration",
			"context-insensitive-plus"           : "ContextInsensitivePlusConfiguration",
			"context-insensitive-plusplus"       : "ContextInsensitivePlusPlusConfiguration",
			"1-call-site-sensitive"              : "OneCallSiteSensitiveConfiguration",
			"1-call-site-sensitive+heap"         : "OneCallSiteSensitivePlusHeapConfiguration",
			"1-type-sensitive"                   : "OneTypeSensitiveConfiguration",
			"1-type-sensitive+heap"              : "OneTypeSensitivePlusHeapConfiguration",
			"1-object-sensitive"                 : "OneObjectSensitiveConfiguration",
			"1-object-sensitive+heap"            : "OneObjectSensitivePlusHeapConfiguration",
			"2-call-site-sensitive"              : "TwoCallSiteSensitiveConfiguration",
			"2-call-site-sensitive+heap"         : "TwoCallSiteSensitivePlusHeapConfiguration",
			"2-call-site-sensitive+2-heap"       : "TwoCallSiteSensitivePlusTwoHeapConfiguration",
			"2-type-sensitive"                   : "TwoTypeSensitiveConfiguration",
			"2-type-sensitive+heap"              : "TwoTypeSensitivePlusHeapConfiguration",
			"2-object-sensitive"                 : "TwoObjectSensitiveConfiguration",
			"2-object-sensitive+heap"            : "TwoObjectSensitivePlusHeapConfiguration",
			"fully-guided-context-sensitive"     : "FullyGuidedContextSensitiveConfiguration",
			"special-2-type-sensitive+heap"      : "SpecialTwoTypeSensitivePlusHeapConfiguration",
			"2-object-sensitive+2-heap"          : "TwoObjectSensitivePlusTwoHeapConfiguration",
			"3-object-sensitive+3-heap"          : "ThreeObjectSensitivePlusThreeHeapConfiguration",
			"2-type-object-sensitive+heap"       : "TwoTypeObjectSensitivePlusHeapConfiguration",
			"2-type-object-sensitive+2-heap"     : "TwoTypeObjectSensitivePlusTwoHeapConfiguration",
			"3-type-sensitive+2-heap"            : "ThreeTypeSensitivePlusTwoHeapConfiguration",
			"3-type-sensitive+3-heap"            : "ThreeTypeSensitivePlusThreeHeapConfiguration",
			"selective-2-object-sensitive+heap"  : "SelectiveTwoObjectSensitivePlusHeapConfiguration",
			"partitioned-2-object-sensitive+heap": "PartitionedTwoObjectSensitivePlusHeapConfiguration",
	]

	/**
	 * Creates a new analysis, verifying the correctness of its name, options and inputFiles using
	 * the default input resolution mechanism.
	 */
	@Override
	DoopAnalysis newAnalysis(AnalysisFamily family, Map<String, AnalysisOption> options) {
		def context
		def platformName = options.PLATFORM.value as String
		if (platformName.contains("python")) {
			context = new DefaultInputResolutionContext(DefaultInputResolutionContext.PYTHON_RESOLVER)
		} else {
			context = newJavaDefaultInputResolutionContext()
		}
		context.add(options.INPUTS.value as List<String>, InputType.INPUT)
		context.add(options.LIBRARIES.value as List<String>, InputType.LIBRARY)

		def platformFiles = new PlatformManager(options.PLATFORMS_LIB.value as String).find(platformName)
		context.add(platformFiles, InputType.PLATFORM)

		context.add(options.HEAPDLS.value as List<String>, InputType.HEAPDL)
		return newAnalysis(options, context)
	}

	/**
	 * Creates a new analysis, verifying the correctness of its id, name, options and inputFiles using
	 * the supplied input resolution mechanism.
	 * If the supplied id is empty or null, an id will be generated automatically.
	 * Otherwise the id will be validated:
	 * - if it is valid, it will be used to identify the analysis,
	 * - if it is invalid, an exception will be thrown.
	 */
	DoopAnalysis newAnalysis(Map<String, AnalysisOption> options, InputResolutionContext context) {
		processOptions(options, context)
		// If not empty or null
		def id = options.USER_SUPPLIED_ID.value as String
		options.USER_SUPPLIED_ID.value = id ? validateUserSuppliedId(id) : generateId(options)

		checkAnalysis(options)
		if (options.LB3.value) {
			checkLogicBlox(options)
			if (options.ANDROID.value) {
				log.warn "WARNING: Using legacy Android processing."
				options.LEGACY_ANDROID_PROCESSING.value = true
			}
		}

		options.CONFIGURATION.value = availableConfigurations.get(options.ANALYSIS.value)

		// Initialize the environment used for executing commands
		def commandsEnv = initExternalCommandsEnvironment(options)
		createOutputDirectory(options)

		if (!options.X_START_AFTER_FACTS.value && !options.CACHE.value) {
			checkAppGlob(options)
		}

		log.debug "Created new analysis"
		if (options.LB3.value)
			return new LB3Analysis(options, context, commandsEnv)
		else {
			if(options.PYTHON.value) {
				return new SoufflePythonAnalysis(options, context, commandsEnv)
			}
			else {
				if (options.USER_DEFINED_PARTITIONS.value)
					return new SoufflePartitionedAnalysis(options, context, commandsEnv)
				else {
					if (options.ANALYSIS.value == "fully-guided-context-sensitive") {
						return new SouffleMultiPhaseAnalysis(options, context, commandsEnv)
					}
					else {
						return new SouffleAnalysis(options, context, commandsEnv)
					}

				}
			}
		}
	}

	/**
	 * Checks that, when reusing facts, options that modify facts do not cause
	 * problems.
	 *
	 * @param factsOpt       the facts-reusing option that has been enabled
	 * @param options        the analysis options
	 * @param throwError     if true, then throw an error, otherwise report a warning
	 */
	static void checkFactsReuse(AnalysisOption factsOpt, Map<String, AnalysisOption> options,
								boolean throwError) {
		def factOpts = options.values().findAll { it.forCacheID && it.value && it.cli }
		for (def opt : factOpts) {
			if (opt.forPreprocessor) {
				log.warn "WARNING: Using option --${opt.name} but facts may not be modified (only logic will be affected)."
			} else {
				if (options.X_SYMLINK_CACHED_FACTS.value) {
					throw new RuntimeException("Option --${opt.name} modifies facts, cannot be used with --${options.X_SYMLINK_CACHED_FACTS.name}.")
				} else if (throwError) {
					throw new RuntimeException("Option --${opt.name} modifies facts, cannot be used with --${factsOpt.name}, use --${options.X_EXTEND_FACTS.name} instead.")
				} else {
					log.warn "WARNING: Option --${opt.name} modifies facts, the copy of the facts may be extended (since option --${factsOpt.name} is on)."
				}
			}
		}
	}

	/**
	 * Return the directory containing facts that will be reused.
	 *
	 * @param factsOpt     the facts-reusing option that has been enabled
	 * @param options      the analysis options
	 * @param throwError   if true, then throw an error, otherwise report a warning
	 * @return             the directory containing facts to reuse
	 */
	static File getFactsReuseDir(AnalysisOption factsOpt, Map<String, AnalysisOption> options,
								 boolean throwError) {
		checkFactsReuse(factsOpt, options, throwError)
		File cacheDir = new File(factsOpt.value as String)
		FileOps.findDirOrThrow(cacheDir, "Invalid user-provided facts directory: $cacheDir")
		return cacheDir
	}

	// Throw an error when two incompatible options are set.
	static void throwIfBothSet(AnalysisOption opt1, AnalysisOption opt2) {
		if (opt1?.value && opt2?.value)
			throw new DoopErrorCodeException(28, "Error: options --${opt1.name} and --${opt2.name} are mutually exclusive.")
	}

	static void checkAnalysis(Map<String, AnalysisOption> options) {
		def name = options.ANALYSIS.value
		log.debug "Verifying analysis name: $name"
		if (options.LB3.value)
			FileOps.findFileOrThrow("${Doop.analysesPath}/${name}/analysis.logic", "Unsupported analysis: $name")
		else
			FileOps.findFileOrThrow("${Doop.souffleAnalysesPath}/${name}/analysis.dl", "Unsupported analysis: $name")
	}

	// This method may not be static, see [Note] above.
	protected String validateUserSuppliedId(String id) {
		def trimmed = id.trim()
		def isValid = trimmed.toCharArray().every {
			c -> Character.isLetter(c) || Character.isDigit(c) || c in EXTRA_ID_CHARACTERS
		}
		if (!isValid)
			throw new RuntimeException("Invalid analysis id: $id. The id should contain only letters, digits, " +
					"${EXTRA_ID_CHARACTERS.collect { "'$it'" }.join(', ')}.")
		return trimmed
	}

	// This method may not be static, see [Note] above.
	private String getOutputDirectory(Map<String, AnalysisOption> options) {
		return "${Doop.doopOut}/${options.ANALYSIS.value}/${options.USER_SUPPLIED_ID.value}"
	}

	// This method may not be static, see [Note] above.
	protected File createOutputDirectory(Map<String, AnalysisOption> options) {
		def outDir = new File(getOutputDirectory(options))
		FileUtils.deleteQuietly(outDir)
		outDir.mkdirs()
		FileOps.findDirOrThrow(outDir, "Could not create analysis directory: ${outDir}")
		options.OUT_DIR.value = outDir
		return outDir
	}

	protected String generateId(Map<String, AnalysisOption> options) {
		Collection<String> idComponents = options.keySet()
				.findAll { !(it in Doop.OPTIONS_EXCLUDED_FROM_ID_GENERATION) }
				.collect { options[it] as String }
		idComponents = options.INPUTS.value + options.LIBRARIES.value + idComponents
		log.debug "ID components: $idComponents"
		def id = idComponents.join('-')

		return CheckSum.checksum(id, HASH_ALGO)
	}

	private static String generateCacheID(Map<String, AnalysisOption> options) {
		Collection<String> idComponents = options.values()
			.findAll { it.forCacheID }
			.collect { it as String }

		Collection<String> checksums = DoopAnalysisFamily.getAllInputs(options)
			.collectMany { File file -> CheckSum.checksumList(file, HASH_ALGO) }

		// Also calculate checksums on all other options that import
		// files into facts.
		Collection<AnalysisOption> miscFileOpts = options.values()
			.findAll { it.forCacheID && it.argInputType == InputType.MISC && it.value }
		for (AnalysisOption opt : miscFileOpts) {
			if (opt != options.TAMIFLEX || opt.value != "dummy") {
				checksums += [CheckSum.checksum(new File(opt.value as String), HASH_ALGO)]
			}
		}

		idComponents = checksums + idComponents

		log.debug "Cache ID components: $idComponents"
		def id = idComponents.join('-')

		return CheckSum.checksum(id, HASH_ALGO)
	}

	/**
	 * Set options according to the platform used. This functionality
	 * is independent of fact generation and is used to turn on
	 * preprocessor flags in the analysis logic.
	 *
	 * @param options the Doop options to affect
	 * @param platformName the platform ("java_8", "android_25_fulljars")
	 */
	private static void setOptionsForPlatform(Map<String, AnalysisOption> options, String platformName) {
		def (platform, version) = platformName.split("_")
		if (platform == "java") {
			// Generate the JRE constant for the preprocessor
			def jreOption = new BooleanAnalysisOption(
					id: "JRE1$version" as String,
					value: true,
					forPreprocessor: true
			)
			options[(jreOption.id)] = jreOption
		} else if (platform == "android") {
			options.ANDROID.value = true
		} else if (platform == "python") {
			options.PYTHON.value = true
		} else {
			throw new RuntimeException("No valid option for ${platformName}")
		}
	}

	/**
	 * Processes the options of the analysis.
	 */
	static void processOptions(Map<String, AnalysisOption> options, InputResolutionContext context) {
		log.debug "Processing analysis options"
		def platformName = options.PLATFORM.value as String

		if (options.X_STOP_AT_FACTS.value) {
			// Dummy value so the option is not empty, because otherwise it is mandatory
			// Must be a valid one
			options.ANALYSIS.value = "context-insensitive"
		}

		// Inputs are optional when reusing facts (but the 'cache'
		// option needs them to compute the cache hash identifier).
		if (options.X_START_AFTER_FACTS.value || options.X_EXTEND_FACTS.value) {
			options.INPUTS.isMandatory = false
			options.LIBRARIES.isMandatory = false
		}

		if (!options.X_START_AFTER_FACTS.value) {
			log.debug "Resolving files"
			context.resolve()

			options.INPUTS.value = context.allInputs
			log.debug "Input file paths: ${context.inputs()} -> ${options.INPUTS.value}"

			options.LIBRARIES.value = context.allLibraries
			log.debug "Library file paths: ${context.libraries()} -> ${options.LIBRARIES.value}"

			options.PLATFORMS.value = context.allPlatformFiles
			log.debug "Platform file paths: ${context.platformFiles()} -> ${options.PLATFORMS.value}"

			options.HEAPDLS.value = context.allHeapDLs
			log.debug "HeapDL file paths: ${context.heapDLs()} -> ${options.HEAPDLS.value}"
		}

		try {
			setOptionsForPlatform(options, platformName)
		} catch (Exception ex) {
			throw new DoopErrorCodeException(29, "Could not process platform ${platformName}, valid platforms are: ${availablePlatforms}")
		}

		if (options.DACAPO.value || options.DACAPO_BACH.value) {
			if (!options.X_START_AFTER_FACTS.value) {
				def libraryPaths = context.libraries()
				def inputJarName = context.inputs().first()
				def deps = inputJarName.replace(".jar", "-deps.jar")
				if (!(deps in libraryPaths)) {
					libraryPaths << deps
					context.resolve()
					options.LIBRARIES.value = context.allLibraries
				}

				if (!options.TAMIFLEX.value)
					options.TAMIFLEX.value = resolveAsInput(inputJarName.replace(".jar", "-tamiflex.log"))

				def benchmark = FilenameUtils.getBaseName(inputJarName)
				log.info "Running ${options.DACAPO.value ? "dacapo" : "dacapo-bach"} benchmark: $benchmark"
			} else {
				if (!options.TAMIFLEX.value)
					options.TAMIFLEX.value = "dummy"
			}
		}

		throwIfBothSet(options.X_START_AFTER_FACTS, options.X_STOP_AT_FACTS)
		throwIfBothSet(options.X_START_AFTER_FACTS, options.X_EXTEND_FACTS)
		throwIfBothSet(options.X_EXTEND_FACTS, options.X_STOP_AT_FACTS)
		throwIfBothSet(options.X_START_AFTER_FACTS, options.CACHE)
		throwIfBothSet(options.KEEP_SPEC, options.X_SYMLINK_CACHED_FACTS)

		if (options.X_SERVER_CHA.value && !options.X_STOP_AT_FACTS.value)
			throw new RuntimeException("Option --${options.X_SERVER_CHA.name} should only be used together with --${options.X_STOP_AT_FACTS.name}.")

		if (options.SKIP_CODE_FACTGEN.value && !options.X_EXTEND_FACTS.value) {
			throw new RuntimeException("Option --${options.SKIP_CODE_FACTGEN.name} should only be used together with --${options.X_EXTEND_FACTS.name}.")
		} else if (options.X_START_AFTER_FACTS.value) {
			options.SKIP_CODE_FACTGEN.value = true
		}

		if (options.TAMIFLEX.value && options.TAMIFLEX.value != "dummy") {
			def tamiflexArg = options.TAMIFLEX.value as String
			options.TAMIFLEX.value = resolveAsInput(tamiflexArg)
			log.info "Using TAMIFLEX information from ${tamiflexArg}"
		}

		if (options.DISTINGUISH_ALL_STRING_BUFFERS.value &&
				options.DISTINGUISH_STRING_BUFFERS_PER_PACKAGE.value) {
			log.warn "WARNING: Multiple distinguish-string-buffer flags. 'All' overrides."
		}

		if (options.NO_MERGE_LIBRARY_OBJECTS.value) {
			options.MERGE_LIBRARY_OBJECTS_PER_METHOD.value = false
		}

		if (options.MERGE_LIBRARY_OBJECTS_PER_METHOD.value && options.CONTEXT_SENSITIVE_LIBRARY_ANALYSIS.value) {
			log.warn "WARNING: Possible inconsistency: context-sensitive library analysis with merged objects."
		}

		if (options.ANALYSIS.value == "types-only" && !options.DISABLE_POINTS_TO.value) {
			log.warn "WARNING: Types-only analysis chosen without disabling points-to reasoning. Disabling it, since this is likely what you want."
			options.DISABLE_POINTS_TO.value = true
		}

		throwIfBothSet(options.DISABLE_POINTS_TO, options.INFORMATION_FLOW)
		throwIfBothSet(options.SOUFFLE_PROVENANCE, options.SOUFFLE_LIVE_PROFILE)

		if (options.SOUFFLE_INCREMENTAL_OUTPUT.value){
			options.SOUFFLE_USE_FUNCTORS.value = true
		}

		throwIfBothSet(options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS, options.DISTINGUISH_ALL_STRING_CONSTANTS)

		if (options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value) {
			options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value = true
			options.DISTINGUISH_ALL_STRING_CONSTANTS.value = false
		}

		if (options.DISTINGUISH_ALL_STRING_CONSTANTS.value) {
			options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value = false
			options.DISTINGUISH_ALL_STRING_CONSTANTS.value = true
		}

		if (options.REFLECTION_METHOD_HANDLES.value &&
			!options.REFLECTION_CLASSIC.value && !options.LIGHT_REFLECTION_GLUE.value) {
			throw new RuntimeException("Error: option " + options.REFLECTION_METHOD_HANDLES.name + " needs one of: " +
									   "--${options.REFLECTION_CLASSIC.name} --${options.LIGHT_REFLECTION_GLUE.name}")
		}

		throwIfBothSet(options.REFLECTION_CLASSIC, options.DISTINGUISH_ALL_STRING_CONSTANTS)
		if (options.REFLECTION_CLASSIC.value) {
			options.DISTINGUISH_ALL_STRING_CONSTANTS.value = false
			options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value = true
			options.REFLECTION.value = true
			options.REFLECTION_SUBSTRING_ANALYSIS.value = true
			options.DISTINGUISH_STRING_BUFFERS_PER_PACKAGE.value = true
			options.TAMIFLEX.value = null
		}

		if (options.LIGHT_REFLECTION_GLUE.value && options.REFLECTION.value) {
			throw new RuntimeException("Error: option --" + options.LIGHT_REFLECTION_GLUE.name + " is not supported when reflection support is enabled.")
		}

		if (options.TAMIFLEX.value) {
			options.REFLECTION.value = false
		}

		// Cached facts and profiling are compatible: profiling
		// commands are added to the .dat file during fact generation.
		if (options.VIA_DDLOG.value && options.CACHE.value && options.SOUFFLE_PROFILE.value) {
			throw new RuntimeException("Error: options --" + options.CACHE.name + " and --" + options.SOUFFLE_PROFILE.name + " are not compatible when running via the DDlog converter.")
		}

		if (options.NO_SSA.value) {
			options.SSA.value = false
		}

		if (options.MUST.value) {
			options.MUST_AFTER_MAY.value = true
		}

		if (options.X_LOW_MEM.value) {
			options.X_SERIALIZE_FACTGEN_COMPILATION.value = true
		}

		// Enable APK decoding on Android when not reusing read-only
		// facts. We don't check the ANDROID option, since the user
		// may want to analyze an .apk using a non-Android platform.
		if (!options.X_START_AFTER_FACTS.value) {
			options.DECODE_APK.value = true
		}

		// Resolution of facts location when reusing facts.
		if (options.X_START_AFTER_FACTS.value) {
			// Facts are assumed to be read-only.
			options.CACHE_DIR.value = getFactsReuseDir(options.X_START_AFTER_FACTS, options, true)
		} else if (options.X_EXTEND_FACTS.value) {
			options.CACHE_DIR.value = getFactsReuseDir(options.X_EXTEND_FACTS, options, false)
		} else {
			def cacheId = generateCacheID(options)
			File cachedFacts = new File(Doop.doopCache, cacheId)
			options.CACHE_DIR.value = cachedFacts
			if (options.CACHE.value && cachedFacts.exists()) {
				// Facts are assumed to be read-only.
				checkFactsReuse(options.CACHE, options, true)
			} else if (options.CACHE.value) {
				log.info "Could not find cached facts, option will be ignored: --${options.CACHE.name}"
				options.CACHE.value = false
			}
		}

		// Handle inerplay between 'main class' information and open programs.
		if (!options.PYTHON.value) {
			if (options.MAIN_CLASS.value) {
				if (options.IGNORE_MAIN_METHOD.value)
					throw new RuntimeException("Option --${options.MAIN_CLASS.name} is not compatible with --${options.IGNORE_MAIN_METHOD.name}")
				else
					log.info "Main class(es) expanded with ${options.MAIN_CLASS.value}"
			} else if (!options.X_START_AFTER_FACTS.value && !options.IGNORE_MAIN_METHOD.value) {
				options.INPUTS.value.each { File jarPath ->
					JarFile jarFile = new JarFile(jarPath)
					//Try to read the main class from the manifest contained in the jar
					String main = jarFile.manifest?.mainAttributes?.getValue(Attributes.Name.MAIN_CLASS) as String
					if (main)
						recordAutoMainClass(options, main)
					else {
						//Check whether the jar contains a class with the same name
						def jarName = FilenameUtils.getBaseName(jarFile.name)
						if (jarFile.getJarEntry("${jarName}.class"))
							recordAutoMainClass(options, jarName)
					}
				}
			}

			if (!options.MAIN_CLASS.value && !options.TAMIFLEX.value &&
				!options.HEAPDLS.value && !options.ANDROID.value &&
				!options.DACAPO.value && !options.DACAPO_BACH.value) {
				if (options.DISCOVER_MAIN_METHODS.value) {
					log.warn "WARNING: No main class was found. Using option --${options.DISCOVER_MAIN_METHODS.name} to discover main methods."
				} else if (options.X_START_AFTER_FACTS.value || options.CACHE.value || options.X_EXTEND_FACTS.value) {
					if (!options.OPEN_PROGRAMS.value)
						log.warn("WARNING: No main class was found and option --${options.OPEN_PROGRAMS.name} is missing. The reused facts are assumed to declare the correct main class(es).")
				} else {
					log.warn "WARNING: No main class was found. This will trigger open-program analysis!"
					if (!options.OPEN_PROGRAMS.value)
						options.OPEN_PROGRAMS.value = "concrete-types"
				}
			}
		}

		if (options.OPEN_PROGRAMS.value && options.ANALYSIS.value == 'micro')
			throw new DoopErrorCodeException(30, "Open-program analysis is not compatible with the 'micro' analysis.")

		if (options.X_DRY_RUN.value && options.CACHE.value) {
			log.warn "WARNING: Doing a dry run of the analysis while using cached facts might be problematic!"
		}

		throwIfBothSet(options.APP_REGEX, options.AUTO_APP_REGEX_MODE)

		if (options.X_SERVER_LOGIC.value) {
			// Turn on optimization outputs.
			if (!(options.GENERATE_OPTIMIZATION_DIRECTIVES.value)) {
				println "Server logic enabled, turning on optimization directives"
				options.GENERATE_OPTIMIZATION_DIRECTIVES.value = true
			}
		}

		if (options.X_SERVER_LOGIC.value || options.GENERATE_OPTIMIZATION_DIRECTIVES.value) {
		   options.GENERATE_ARTIFACTS_MAP.value = true
		}

		// If no stats option is given, select default stats.
		if (!options.X_STATS_FULL.value && !options.X_STATS_DEFAULT.value &&
				!options.X_STATS_NONE.value && !options.X_STATS_AROUND.value) {
			options.X_STATS_DEFAULT.value = true
		}

		if (options.REFLECTION_DYNAMIC_PROXIES.value && !options.REFLECTION.value) {
			String message = "WARNING: Dynamic proxy support without standard reflection support, using custom 'opt-reflective' reflection rules."
			options.LIGHT_REFLECTION_GLUE.value = true
			if (!options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value &&
					!options.DISTINGUISH_ALL_STRING_CONSTANTS.value) {
				message += "\nWARNING: 'opt-reflective' may not work optimally, one of these flags is suggested: --" + options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.name + ", --" + options.DISTINGUISH_ALL_STRING_CONSTANTS.name
			}
			log.warn message
		}

		if (!options.REFLECTION.value) {
			if (options.DISTINGUISH_REFLECTION_ONLY_STRING_CONSTANTS.value ||
					options.REFLECTION_SUBSTRING_ANALYSIS.value ||
					options.REFLECTION_CONTEXT_SENSITIVITY.value ||
					options.REFLECTION_HIGH_SOUNDNESS_MODE.value ||
					options.REFLECTION_SPECULATIVE_USE_BASED_ANALYSIS.value ||
					options.REFLECTION_INVENT_UNKNOWN_OBJECTS.value ||
					options.REFLECTION_REFINED_OBJECTS.value) {
				log.warn "WARNING: Probable inconsistent set of Java reflection flags!"
			} else if (options.LIGHT_REFLECTION_GLUE.value) {
				log.warn "WARNING: Handling of simple Java reflection patterns only!"
			} else if (options.TAMIFLEX.value) {
				log.warn "WARNING: Handling of Java reflection via Tamiflex logic!"
			} else {
				log.warn "WARNING: Handling of Java reflection is disabled!"
			}
		} else if (options.REFLECTION_HIGH_SOUNDNESS_MODE.value) {
			options.EXTRACT_MORE_STRINGS.value = true
		}

		options.values().each {
			if (it.argName && it.value && it.validValues && !(it.value in it.validValues))
				throw new RuntimeException("Invalid value `$it.value` for option: $it.name")
		}

		options.values().findAll { it.isMandatory }.each {
			if (!it.value)
				throw new RuntimeException("Missing mandatory argument: $it.name")
		}
	}

	/**
	 * Records an auto-detected main class. If reusing read-only facts, it does nothing.
	 *
	 * @param options	   the analysis options
	 * @param className	   the name of the class
	 */
	static void recordAutoMainClass(Map<String, AnalysisOption> options, String className) {
		if (options.CACHE.value)
			log.warn "WARNING: Ignoring auto-detected main class '${className}' when using --${options.CACHE.name}"
		else {
			log.info "Main class(es) expanded with '${className}'"
			(options.MAIN_CLASS.value as List<String>) << className
		}
	}

	static DefaultInputResolutionContext newJavaDefaultInputResolutionContext() {
		return new DefaultInputResolutionContext(DefaultInputResolutionContext.defaultResolver(new File(Doop.doopTmp)))
	}

	static File resolveAsInput(String filePath) {
		def context = newJavaDefaultInputResolutionContext()
		context.add(filePath, InputType.INPUT)
		context.resolve()
		context.allInputs.first()
	}

	/**
	 * Determines application classes.
	 *
	 * If an app regex is not present, it generates one.
	 */
	protected void checkAppGlob(Map<String, AnalysisOption> options) {
		if (!options.APP_REGEX.value) {
			log.debug "Generating app regex"

			Set<String> packages
			String mode = options.AUTO_APP_REGEX_MODE.value
			// Default is 'all'.
			if ((mode == null) || (mode == 'all')) {
				packages = [] as Set
				options.INPUTS.value.each { packages.addAll(PackageUtil.getPackages(it)) }
			} else if (mode == 'first') {
				packages = PackageUtil.getPackages(options.INPUTS.value.first())
			} else {
				throw new RuntimeException("Invalid auto-app-regex mode: ${mode}")
			}

			if (packages.size() == 0)
				throw new DoopErrorCodeException(34, "Automatic app-regex generation failed, do the inputs contain valid Java code?")

			options.APP_REGEX.value = packages.sort().join(':')
			log.debug "APP_REGEX: ${options.APP_REGEX.value}"
		}
	}

	/**
	 * Verifies the correctness of the LogicBlox related options
	 */
	protected void checkLogicBlox(Map<String, AnalysisOption> options) {
		//BLOX_OPTS is set by the main method
		def lbhome = options.LOGICBLOX_HOME
		log.debug "Verifying LogicBlox home: ${lbhome.value}"
		def lbHomeDir = FileOps.findDirOrThrow(lbhome.value as String, "The ${lbhome.id} value is invalid: ${lbhome.value}")

		def oldldpath = System.getenv("LD_LIBRARY_PATH")
		options.LD_LIBRARY_PATH.value = lbHomeDir.absolutePath + "/bin" + ":" + oldldpath
		def bloxbatch = lbHomeDir.absolutePath + "/bin/bloxbatch"
		FileOps.findFileOrThrow(bloxbatch, "The bloxbatch file is invalid: $bloxbatch")
		options.BLOXBATCH.value = bloxbatch
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
	protected Map<String, String> initExternalCommandsEnvironment(Map<String, AnalysisOption> options) {
		log.debug "Initializing the environment of the external commands"

		Map<String, String> env = [:]
		env.putAll(System.getenv())
		env.ANALYSIS_OUT = "${getOutputDirectory(options)}/database" as String
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

	static final Set<String> getAvailablePlatforms() { PlatformManager.ARTIFACTS_FOR_PLATFORM.keySet() }
}
