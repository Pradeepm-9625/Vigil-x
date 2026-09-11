package com.vigilx.apisecurity.performance;

import java.util.List;

import com.vigilx.apisecurity.inventory.ApiDefinition;

/**
 * Generates a minimal, valid JMeter 5.6 (.jmx) test plan programmatically - one Thread Group per
 * call, an HTTP Header Manager carrying the Bearer token obtained <em>once</em> (JMeter virtual
 * users never each log in - that would multiply real login attempts against the app's own
 * rate-limited {@code /auth/login}), a Constant Timer for a small think-time between requests per
 * virtual user, one HTTP Request sampler per API, and a Simple Data Writer producing the .jtl
 * results file this module's own report is built from.
 *
 * <p>Never generates a sampler for a write method: every {@link ApiDefinition} passed in must
 * already be GET-only (see {@code ApiExecutionPolicy}) - this class does not filter, the caller must.
 */
public final class JmeterPlanBuilder {

    private JmeterPlanBuilder() {
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
        xml.append("        <collectionProp name=\"Arguments.arguments\"/>\n");
        xml.append("      </elementProp>\n");
        xml.append("    </TestPlan>\n");
        xml.append("    <hashTree>\n");

        appendThreadGroup(xml, testPlanName, profile);
        xml.append("      <hashTree>\n");

        appendHttpDefaults(xml, host, port);
        xml.append("        <hashTree/>\n");

        appendHeaderManager(xml, bearerToken);
        xml.append("        <hashTree/>\n");

        appendConstantTimer(xml, profile.thinkTimeMs());
        xml.append("        <hashTree/>\n");

        for (ApiDefinition definition : getApis) {
            appendHttpSampler(xml, definition);
            xml.append("        <hashTree/>\n");
        }

        xml.append("      </hashTree>\n");
        xml.append("    </hashTree>\n");

        appendResultCollector(xml);
        xml.append("    <hashTree/>\n");

        xml.append("  </hashTree>\n");
        xml.append("</jmeterTestPlan>\n");
        return xml.toString();
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
        xml.append("          <stringProp name=\"HTTPSampler.domain\">").append(escape(host)).append("</stringProp>\n");
        xml.append("          <stringProp name=\"HTTPSampler.port\">").append(port).append("</stringProp>\n");
        xml.append("          <stringProp name=\"HTTPSampler.protocol\">http</stringProp>\n");
        xml.append("        </ConfigTestElement>\n");
    }

    private static void appendHeaderManager(StringBuilder xml, String bearerToken) {
        xml.append("        <HeaderManager guiclass=\"HeaderPanel\" testclass=\"HeaderManager\" "
                + "testname=\"HTTP Header Manager\" enabled=\"true\">\n");
        xml.append("          <collectionProp name=\"HeaderManager.headers\">\n");
        appendHeader(xml, "Authorization", "Bearer " + bearerToken);
        appendHeader(xml, "Accept", "application/json");
        appendHeader(xml, "instance", "web");
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

    private static void appendHttpSampler(StringBuilder xml, ApiDefinition definition) {
        String path = definition.samplePath()
                + (definition.sampleQuery() == null || definition.sampleQuery().isBlank()
                        ? "" : "?" + definition.sampleQuery());
        String name = definition.method() + " " + definition.normalizedPath();
        xml.append("        <HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" testclass=\"HTTPSamplerProxy\" "
                + "testname=\"").append(escape(name)).append("\" enabled=\"true\">\n");
        xml.append("          <stringProp name=\"HTTPSampler.path\">").append(escape(path)).append("</stringProp>\n");
        xml.append("          <stringProp name=\"HTTPSampler.method\">").append(definition.method()).append("</stringProp>\n");
        xml.append("          <boolProp name=\"HTTPSampler.follow_redirects\">true</boolProp>\n");
        xml.append("          <boolProp name=\"HTTPSampler.use_keepalive\">true</boolProp>\n");
        xml.append("        </HTTPSamplerProxy>\n");
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
        xml.append("          <assertions>false</assertions><subresults>false</subresults>\n");
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
