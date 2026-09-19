package com.vigilx.apisecurity.performance;

import java.util.List;

import com.vigilx.apisecurity.inventory.ApiDefinition;
import com.vigilx.apisecurity.performance.CrudLifecycleGrouper.CrudGroup;
import com.vigilx.apisecurity.performance.CrudLifecycleGrouper.GroupingResult;

/**
 * Generates the "load test" JMeter plan: the same proven structure {@link JmeterPlanBuilder} already
 * uses (one Thread Group, one Header Manager carrying the token obtained once, a Constant Timer, one
 * HTTP Request sampler per API, a Simple Data Writer) plus one addition that class deliberately does
 * not have - a Response Assertion under every sampler, checking the live response code against the
 * status this exact API was actually observed returning in the real capture
 * ({@link ApiDefinition#sampleStatus()}). That is the "expected result" a load-test PASS/FAIL is
 * judged against - never invented, never a blanket "any 2xx".
 *
 * <p>A separate class rather than a change to {@link JmeterPlanBuilder}: that class is already relied
 * on by {@code JmeterPerformanceTest}, and its own javadoc is explicit that it "never filters" and
 * emits exactly what it is given - adding an assertion there would change its output for every
 * existing caller. This class is additive only.
 */
public final class LoadTestPlanBuilder {

    private LoadTestPlanBuilder() {
    }

    public static String build(String testPlanName, String host, int port, String bearerToken,
                               List<ApiDefinition> getApis, LoadProfile profile) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n");
        xml.append("  <hashTree>\n");
        xml.append("    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"")
                .append(escape(testPlanName)).append("\" enabled=\"true\">\n");
        xml.append("      <boolProp name=\"TestPlan.functional_mode\">false</boolProp>\n");
        xml.append("      <boolProp name=\"TestPlan.tearDown_on_shutdown\">true</boolProp>\n");
        xml.append("      <boolProp name=\"TestPlan.serialize_threadgroups\">false</boolProp>\n");
        xml.append("      <elementProp name=\"TestPlan.user_defined_variables\" elementType=\"Arguments\" "
                + "guiclass=\"ArgumentsPanel\" testclass=\"Arguments\" testname=\"User Defined Variables\" "
                + "enabled=\"true\">\n");
        xml.append("        <collectionProp name=\"Arguments.arguments\">\n");
        // Seeded with whatever token was available at generation time (real for a plan built via
        // ApiAuthClient.login(), empty for a soak run's own captured-APIs plan with no REST login) -
        // never left undefined, so ${accessToken} always resolves to SOMETHING from the very first
        // sampler, even before any login sampler in the plan itself has run. The login sampler below
        // (when the captured APIs include one) then OVERWRITES this with the real, freshly-observed
        // token via its own JSON Extractor, so every sampler after it carries a live token instead of
        // a stale/empty one.
        xml.append("          <elementProp name=\"accessToken\" elementType=\"Argument\">\n");
        xml.append("            <stringProp name=\"Argument.name\">accessToken</stringProp>\n");
        xml.append("            <stringProp name=\"Argument.value\">").append(escape(bearerToken))
                .append("</stringProp>\n");
        xml.append("            <stringProp name=\"Argument.metadata\">=</stringProp>\n");
        xml.append("          </elementProp>\n");
        xml.append("        </collectionProp>\n");
        xml.append("      </elementProp>\n");
        xml.append("    </TestPlan>\n");
        xml.append("    <hashTree>\n");

        appendThreadGroup(xml, testPlanName, profile);
        xml.append("      <hashTree>\n");

        appendHttpDefaults(xml, host, port);
        xml.append("        <hashTree/>\n");

        appendHeaderManager(xml);
        xml.append("        <hashTree/>\n");

        appendConstantTimer(xml, profile.thinkTimeMs());
        xml.append("        <hashTree/>\n");

        // Real CREATE -> UPDATE -> DELETE lifecycles first, discovered purely from the inventory's
        // own POST-base / PUT-PATCH-DELETE-{id} shape - never a hard-coded resource list. Placed
        // ahead of every standalone sampler so a dependent write never has a chance to run before the
        // data it needs exists (this thread group has exactly one flat sampler sequence per
        // iteration, executed top to bottom).
        GroupingResult grouping = CrudLifecycleGrouper.group(getApis);
        for (CrudGroup group : grouping.groups()) {
            appendCrudLifecycle(xml, group);
        }

        // A captured login call (POST .../login) runs before every other standalone sampler, never
        // wherever it happened to land in the raw captured order - its JSON Extractor (below) must
        // execute before anything that depends on ${accessToken}, and JMeter runs one thread's
        // samplers top-to-bottom within an iteration. Every other standalone sampler keeps its
        // original relative order (a stable partition, not a full re-sort).
        List<ApiDefinition> orderedStandalone = new java.util.ArrayList<>();
        for (ApiDefinition definition : grouping.standalone()) {
            if (isLoginApi(definition)) {
                orderedStandalone.add(definition);
            }
        }
        for (ApiDefinition definition : grouping.standalone()) {
            if (!isLoginApi(definition)) {
                orderedStandalone.add(definition);
            }
        }

        for (ApiDefinition definition : orderedStandalone) {
            appendHttpSampler(xml, definition, null);
            // The sampler's own hashTree now carries its Response Assertion as a child - the
            // assertion travels with (and only applies to) this one sampler, exactly like the JMeter
            // GUI itself nests it, never a sibling that could be mistaken for applying test-plan-wide.
            xml.append("        <hashTree>\n");
            appendResponseAssertion(xml, definition);
            xml.append("          <hashTree/>\n");
            if (isLoginApi(definition)) {
                // Overwrites the seeded ${accessToken} (see TestPlan.user_defined_variables above)
                // with the real token this exact login response returned - $.accessToken is the
                // same field ApiAuthClient.login() already parses, never a guessed field name.
                // appendJsonExtractor() emits its own trailing hashTree, so nothing else is added
                // after it - an extra one here was found live to corrupt the plan (the same
                // duplicate-hashTree class of bug appendCrudLifecycle() already had and was fixed).
                appendJsonExtractor(xml, "accessToken", "$.accessToken");
            }
            xml.append("        </hashTree>\n");
        }

        xml.append("      </hashTree>\n");
        xml.append("    </hashTree>\n");

        appendResultCollector(xml);
        xml.append("    <hashTree/>\n");

        xml.append("  </hashTree>\n");
        xml.append("</jmeterTestPlan>\n");
        return xml.toString();
    }

    /**
     * A real captured login call - {@code POST .../login} (e.g. {@code /auth/login}) - the same
     * shape {@link com.vigilx.apisecurity.execution.ApiAuthClient} calls. Never anything named
     * merely "auth" (a GET auth-status/refresh endpoint is not a login), and never a partial-name
     * accident: the path's own last segment must be exactly {@code login}.
     */
    private static boolean isLoginApi(ApiDefinition definition) {
        if (!"POST".equals(definition.method()) || definition.normalizedPath() == null) {
            return false;
        }
        String path = definition.normalizedPath().toLowerCase(java.util.Locale.ROOT);
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return "login".equals(lastSegment);
    }

    private static void appendThreadGroup(StringBuilder xml, String name, LoadProfile profile) {
        xml.append("    <ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" testname=\"")
                .append(escape(name)).append("\" enabled=\"true\">\n");
        xml.append("      <stringProp name=\"ThreadGroup.on_sample_error\">continue</stringProp>\n");
        xml.append("      <elementProp name=\"ThreadGroup.main_controller\" elementType=\"LoopController\" "
                + "guiclass=\"LoopControlPanel\" testclass=\"LoopController\" testname=\"Loop Controller\" "
                + "enabled=\"true\">\n");
        xml.append("        <boolProp name=\"LoopController.continue_forever\">false</boolProp>\n");
        xml.append("        <stringProp name=\"LoopController.loops\">-1</stringProp>\n");
        xml.append("      </elementProp>\n");
        xml.append("      <stringProp name=\"ThreadGroup.num_threads\">").append(profile.users()).append("</stringProp>\n");
        xml.append("      <stringProp name=\"ThreadGroup.ramp_time\">").append(profile.rampUpSeconds()).append("</stringProp>\n");
        xml.append("      <boolProp name=\"ThreadGroup.scheduler\">true</boolProp>\n");
        xml.append("      <stringProp name=\"ThreadGroup.duration\">").append(profile.durationSeconds()).append("</stringProp>\n");
        xml.append("      <stringProp name=\"ThreadGroup.delay\"></stringProp>\n");
        xml.append("      <boolProp name=\"ThreadGroup.same_user_on_next_iteration\">true</boolProp>\n");
        xml.append("    </ThreadGroup>\n");
    }

    private static void appendHttpDefaults(StringBuilder xml, String host, int port) {
        xml.append("        <ConfigTestElement guiclass=\"HttpDefaultsGui\" testclass=\"ConfigTestElement\" "
                + "testname=\"HTTP Request Defaults\" enabled=\"true\">\n");
        // JMeter's own "HTTP Request Defaults" element always carries an Arguments elementProp, even
        // empty - confirmed live (running this plan in the real JMeter GUI): without it, JMeter logs
        // "Property HTTPsampler.Arguments is unset for element ConfigTestElement@..." for this exact
        // element on load.
        xml.append("          <elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\" "
                + "guiclass=\"HTTPArgumentsPanel\" testclass=\"Arguments\" testname=\"User Defined Variables\" "
                + "enabled=\"true\">\n");
        xml.append("            <collectionProp name=\"Arguments.arguments\"/>\n");
        xml.append("          </elementProp>\n");
        xml.append("          <stringProp name=\"HTTPSampler.domain\">").append(escape(host)).append("</stringProp>\n");
        xml.append("          <stringProp name=\"HTTPSampler.port\">").append(port).append("</stringProp>\n");
        xml.append("          <stringProp name=\"HTTPSampler.protocol\">http</stringProp>\n");
        xml.append("        </ConfigTestElement>\n");
    }

    private static void appendHeaderManager(StringBuilder xml) {
        xml.append("        <HeaderManager guiclass=\"HeaderPanel\" testclass=\"HeaderManager\" "
                + "testname=\"HTTP Header Manager\" enabled=\"true\">\n");
        xml.append("          <collectionProp name=\"HeaderManager.headers\">\n");
        // ${accessToken} - a real JMeter variable (seeded in TestPlan.user_defined_variables above,
        // refreshed by the login sampler's own JSON Extractor when the captured APIs include one) -
        // never a value baked in once at generation time, which is exactly what left every sampler
        // after login sending an empty/stale Authorization header (confirmed live).
        appendHeader(xml, "Authorization", "Bearer ${accessToken}");
        appendHeader(xml, "Accept", "application/json");
        appendHeader(xml, "instance", "web");
        // Only meaningful for the write methods this test plan can now include - a GET sampler
        // simply ignores it, so adding it unconditionally here changes nothing for GET.
        appendHeader(xml, "Content-Type", "application/json");
        xml.append("          </collectionProp>\n");
        xml.append("        </HeaderManager>\n");
    }

    private static void appendHeader(StringBuilder xml, String name, String value) {
        xml.append("            <elementProp name=\"\" elementType=\"Header\">\n");
        xml.append("              <stringProp name=\"Header.name\">").append(escape(name)).append("</stringProp>\n");
        xml.append("              <stringProp name=\"Header.value\">").append(escape(value)).append("</stringProp>\n");
        xml.append("            </elementProp>\n");
    }

    private static void appendConstantTimer(StringBuilder xml, int thinkTimeMs) {
        xml.append("        <ConstantTimer guiclass=\"ConstantTimerGui\" testclass=\"ConstantTimer\" "
                + "testname=\"Think Time\" enabled=\"true\">\n");
        xml.append("          <stringProp name=\"ConstantTimer.delay\">").append(thinkTimeMs).append("</stringProp>\n");
        xml.append("        </ConstantTimer>\n");
    }

    /**
     * One real CREATE -&gt; UPDATE -&gt; DELETE sequence for one resource: the CREATE sampler carries
     * its own real captured body and a JSON Extractor that captures {@code $.id} from its real
     * response into {@code group.variableName()}; UPDATE and DELETE (when the inventory actually
     * captured either) use the SAME variable in place of whatever id they were originally captured
     * with, each wrapped in an If Controller that only runs it when the id was actually captured this
     * iteration - never a blind attempt against an empty/unresolved variable, and never a fabricated
     * id when CREATE itself failed or returned no recognizable id.
     */
    private static void appendCrudLifecycle(StringBuilder xml, CrudGroup group) {
        ApiDefinition create = group.create();
        appendHttpSampler(xml, create, null);
        xml.append("        <hashTree>\n");
        appendResponseAssertion(xml, create);
        xml.append("          <hashTree/>\n");
        appendJsonExtractor(xml, group.variableName());
        xml.append("        </hashTree>\n");

        if (group.update() != null) {
            appendIfControllerWrapped(xml, group.variableName(), "UPDATE " + group.resourceName(), () -> {
                appendHttpSampler(xml, group.update(), group.variableName());
                xml.append("            <hashTree>\n");
                appendResponseAssertion(xml, group.update());
                xml.append("              <hashTree/>\n");
                xml.append("            </hashTree>\n");
            });
        }

        if (group.delete() != null) {
            appendIfControllerWrapped(xml, group.variableName(), "DELETE " + group.resourceName(), () -> {
                appendHttpSampler(xml, group.delete(), group.variableName());
                xml.append("            <hashTree>\n");
                appendResponseAssertion(xml, group.delete());
                xml.append("              <hashTree/>\n");
                xml.append("            </hashTree>\n");
            });
        }
    }

    /**
     * Extracts {@code $.id} from the CREATE sampler's own real JSON response into {@code variableName} -
     * the one real place a "newly created record id" can honestly come from. {@code match_numbers=1}
     * takes the first match only; when the response carries no {@code id} field at all, the variable
     * is simply left unset (JMeter's own default JSONPostProcessor behavior) - never a fabricated value.
     */
    private static void appendJsonExtractor(StringBuilder xml, String variableName) {
        appendJsonExtractor(xml, variableName, "$.id");
    }

    /**
     * Same as {@link #appendJsonExtractor(StringBuilder, String)} but for any JSONPath, not just the
     * CRUD chain's own {@code $.id} - used to extract the real login response's {@code accessToken}
     * (the exact field {@link com.vigilx.apisecurity.execution.ApiAuthClient} already parses) so every
     * sampler after the login one carries a real, freshly-observed token instead of a value baked in
     * once at generation time, which is stale/empty whenever this plan was built without a live REST
     * login (e.g. a soak run's own captured-APIs JMX).
     */
    private static void appendJsonExtractor(StringBuilder xml, String variableName, String jsonPathExpr) {
        xml.append("          <JSONPostProcessor guiclass=\"JSONPostProcessorGui\" testclass=\"JSONPostProcessor\" "
                + "testname=\"Extract ").append(escape(variableName)).append("\" enabled=\"true\">\n");
        xml.append("            <stringProp name=\"JSONPostProcessor.referenceNames\">")
                .append(escape(variableName)).append("</stringProp>\n");
        xml.append("            <stringProp name=\"JSONPostProcessor.jsonPathExprs\">")
                .append(escape(jsonPathExpr)).append("</stringProp>\n");
        xml.append("            <stringProp name=\"JSONPostProcessor.match_numbers\">1</stringProp>\n");
        xml.append("            <boolProp name=\"JSONPostProcessor.compute_concat\">false</boolProp>\n");
        xml.append("          </JSONPostProcessor>\n");
        xml.append("          <hashTree/>\n");
    }

    /**
     * Wraps {@code inner} (a full sampler+hashTree block, appended by the caller) in an If Controller
     * that only enters when {@code variableName} was actually resolved this iteration.
     *
     * <p>Deliberately queries {@code vars.get("name")} from inside the JavaScript rather than relying
     * on {@code ${name}} string substitution in the condition text: JMeter substitutes an undefined
     * variable reference with an empty string (not the literal unresolved {@code ${name}} token,
     * confirmed live - a plausible-looking but wrong idiom this class used before), which would make
     * an "is the substituted text still the literal name" check always true regardless of whether the
     * JSON Extractor actually ran. {@code vars.get(...)} returns real Java {@code null} when the
     * variable was never set, so checking for real {@code null} is reliable evidence the JSON
     * Extractor's referenced-name variable was never touched. That alone is not sufficient, though:
     * a JSON Extractor with no match sets the variable to an empty string rather than leaving it
     * {@code null} (confirmed live - real chained samples still fired with an empty id segment in
     * the URL using a {@code != null}-only check), so this also requires non-empty content.
     */
    private static void appendIfControllerWrapped(StringBuilder xml, String variableName, String label,
                                                   Runnable inner) {
        xml.append("        <IfController guiclass=\"IfControllerPanel\" testclass=\"IfController\" testname=\"If ")
                .append(escape(variableName)).append(" captured (").append(escape(label)).append(")\" enabled=\"true\">\n");
        String condition = "${__javaScript(vars.get(\"" + variableName + "\") != null && vars.get(\""
                + variableName + "\").length() > 0)}";
        xml.append("          <stringProp name=\"IfController.condition\">").append(escape(condition))
                .append("</stringProp>\n");
        xml.append("          <boolProp name=\"IfController.evaluateAll\">false</boolProp>\n");
        xml.append("        </IfController>\n");
        xml.append("        <hashTree>\n");
        inner.run();
        xml.append("        </hashTree>\n");
    }

    private static void appendHttpSampler(StringBuilder xml, ApiDefinition definition, String idVariableOverride) {
        String path = pathFor(definition, idVariableOverride)
                + (definition.sampleQuery() == null || definition.sampleQuery().isBlank()
                        ? "" : "?" + definition.sampleQuery());
        // Deliberately NOT "${var}" here: JMeter evaluates ${...} function/variable syntax inside a
        // sampler's own testname at run time, so a literal "(${var})" would never survive into the
        // real JTL label - it would show up resolved (the real captured id) or empty, different every
        // iteration, which is exactly what silently broke chained-sample aliasing before this fix. A
        // "[chained:var]" suffix contains no JMeter function syntax, so it always stays exactly as
        // written and the analyzer can match it back to this same literal string, every time.
        String name = idVariableOverride == null ? definition.method() + " " + definition.normalizedPath()
                : definition.method() + " " + definition.normalizedPath() + " [chained:" + idVariableOverride + "]";
        xml.append("        <HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" testclass=\"HTTPSamplerProxy\" "
                + "testname=\"").append(escape(name)).append("\" enabled=\"true\">\n");
        xml.append("          <stringProp name=\"HTTPSampler.path\">").append(escape(path)).append("</stringProp>\n");
        xml.append("          <stringProp name=\"HTTPSampler.method\">").append(definition.method()).append("</stringProp>\n");
        xml.append("          <boolProp name=\"HTTPSampler.follow_redirects\">true</boolProp>\n");
        xml.append("          <boolProp name=\"HTTPSampler.use_keepalive\">true</boolProp>\n");
        // This test plan is no longer GET-only (by explicit, informed request): a POST/PUT/PATCH
        // carries its own real captured request body here, exactly as it was really observed - never
        // an invented/synthetic body. GET/DELETE (and any write with no real captured body) stay
        // body-less, exactly as before this existed.
        if (definition.hasSampleRequestBody()) {
            xml.append("          <boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>\n");
            xml.append("          <elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\">\n");
            xml.append("            <collectionProp name=\"Arguments.arguments\">\n");
            xml.append("              <elementProp name=\"\" elementType=\"HTTPArgument\">\n");
            xml.append("                <boolProp name=\"HTTPArgument.always_encode\">false</boolProp>\n");
            xml.append("                <stringProp name=\"Argument.value\">")
                    .append(escape(definition.sampleRequestBody())).append("</stringProp>\n");
            xml.append("                <stringProp name=\"Argument.metadata\">=</stringProp>\n");
            xml.append("              </elementProp>\n");
            xml.append("            </collectionProp>\n");
            xml.append("          </elementProp>\n");
        }
        xml.append("        </HTTPSamplerProxy>\n");
    }

    /**
     * The real captured path, with its trailing {@code {id}} segment (the exact position
     * {@link com.vigilx.apisecurity.inventory.ApiNormalizer} identifies as a resource identifier)
     * replaced by {@code ${idVariable}} when this sampler is part of a CRUD chain - never any other
     * segment, and never touched at all when {@code idVariable} is {@code null} (every standalone
     * sampler keeps its own real captured path exactly as before this existed).
     */
    private static String pathFor(ApiDefinition definition, String idVariable) {
        if (idVariable == null) {
            return definition.samplePath();
        }
        int lastSlash = definition.samplePath().lastIndexOf('/');
        if (lastSlash < 0) {
            return definition.samplePath();
        }
        return definition.samplePath().substring(0, lastSlash + 1) + "${" + idVariable + "}";
    }

    /**
     * One Response Assertion per sampler, checking {@code Assertion.response_code} equals the exact
     * status this API was really observed returning in the capture ({@link ApiDefinition#sampleStatus()}).
     * A definition with no real captured status (0 - never actually observed, only inferred) gets no
     * assertion at all: asserting against a status that was never really seen would be inventing an
     * expectation, not using one.
     */
    private static void appendResponseAssertion(StringBuilder xml, ApiDefinition definition) {
        if (definition.sampleStatus() <= 0) {
            return;
        }
        xml.append("          <ResponseAssertion guiclass=\"AssertionGui\" testclass=\"ResponseAssertion\" "
                + "testname=\"Expected status ").append(definition.sampleStatus()).append("\" enabled=\"true\">\n");
        xml.append("            <collectionProp name=\"Asserion.test_strings\">\n");
        xml.append("              <stringProp name=\"49586\">").append(definition.sampleStatus()).append("</stringProp>\n");
        xml.append("            </collectionProp>\n");
        xml.append("            <stringProp name=\"Assertion.custom_message\"></stringProp>\n");
        xml.append("            <stringProp name=\"Assertion.test_field\">Assertion.response_code</stringProp>\n");
        xml.append("            <boolProp name=\"Assertion.assume_success\">false</boolProp>\n");
        xml.append("            <intProp name=\"Assertion.test_type\">8</intProp>\n");
        xml.append("          </ResponseAssertion>\n");
    }

    private static void appendResultCollector(StringBuilder xml) {
        xml.append("    <ResultCollector guiclass=\"SimpleDataWriter\" testclass=\"ResultCollector\" "
                + "testname=\"Simple Data Writer\" enabled=\"true\">\n");
        xml.append("      <boolProp name=\"ResultCollector.error_logging\">false</boolProp>\n");
        xml.append("      <objProp>\n");
        xml.append("        <name>saveConfig</name>\n");
        xml.append("        <value class=\"SampleSaveConfiguration\">\n");
        xml.append("          <time>true</time><latency>true</latency><timestamp>true</timestamp>\n");
        xml.append("          <success>true</success><label>true</label><code>true</code><message>true</message>\n");
        xml.append("          <threadName>true</threadName><dataType>false</dataType><encoding>false</encoding>\n");
        xml.append("          <assertions>true</assertions><subresults>false</subresults>\n");
        xml.append("          <responseData>false</responseData><samplerData>false</samplerData><xml>false</xml>\n");
        xml.append("          <fieldNames>true</fieldNames><responseHeaders>false</responseHeaders>\n");
        xml.append("          <requestHeaders>false</requestHeaders><responseDataOnError>false</responseDataOnError>\n");
        xml.append("          <saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>\n");
        xml.append("          <assertionsResultsToSave>0</assertionsResultsToSave>\n");
        xml.append("          <bytes>true</bytes><sentBytes>true</sentBytes><threadCounts>true</threadCounts>\n");
        xml.append("          <idleTime>true</idleTime><connectTime>true</connectTime>\n");
        xml.append("        </value>\n");
        xml.append("      </objProp>\n");
        xml.append("      <stringProp name=\"filename\"></stringProp>\n");
        xml.append("    </ResultCollector>\n");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
