package org.clyze.doop

import org.clyze.analysis.Analysis
import spock.lang.Specification
import spock.lang.Unroll
import static org.clyze.doop.TestUtils.*

/**
 * Test micro analysis.
 */
class CrudeScalerTest extends Specification {

	// @spock.lang.Ignore
	@Unroll
	def "Crude testing scaler analysis"() {
		when:
		Main.main((String[])["-i", Artifacts.ANTLR_JAR, "-a", "fully-guided-context-sensitive", "--id", "antlr-scaler", "--dacapo", "--scaler-pre", "--fact-gen-cores", "1", "--platform", "java_8"])
		Analysis analysis = Main.analysis

		then:
		relationHasApproxSize(analysis, "CallGraphEdge", 1086574)
		relationHasApproxSize(analysis, "VarPointsTo", 6643270)
		relationHasApproxSize(analysis, "ApplicationMethod", 2680)
		relationHasApproxSize(analysis, "Reachable", 10311)
	}
}
