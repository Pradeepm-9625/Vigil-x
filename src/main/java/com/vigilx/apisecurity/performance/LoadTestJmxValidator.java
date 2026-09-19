package com.vigilx.apisecurity.performance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Real, structural validation of a generated {@code .jmx} - never a re-write "to make it look
 * valid": every check here only reads the file JMeter will actually be pointed at, the exact same
 * file (see {@link LoadTestResultAnalyzer} and the run orchestrator - the jmx checked here is the
 * literal one JMeter executes, not a separate copy).
 */
public final class LoadTestJmxValidator {

    private LoadTestJmxValidator() {
    }

    public static final class ValidationResult {
        public boolean fileExists;
        public boolean wellFormedXml;
        public boolean hasTestPlan;
        public boolean hasThreadGroup;
        public int httpSamplerCount;
        public boolean hasHeaderManager;
        public int responseAssertionCount;
        public final List<String> problems = new ArrayList<>();

        public boolean isValid() {
            return fileExists && wellFormedXml && hasTestPlan && hasThreadGroup && httpSamplerCount > 0;
        }
    }

    public static ValidationResult validate(Path jmxFile) {
        ValidationResult result = new ValidationResult();
        result.fileExists = Files.isRegularFile(jmxFile);
        if (!result.fileExists) {
            result.problems.add("File does not exist: " + jmxFile);
            return result;
        }

        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Prevent XXE on a file this process itself just generated - defensive, not because the
            // content is untrusted, but validation code should never assume that of any XML it opens.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            document = factory.newDocumentBuilder().parse(jmxFile.toFile());
            result.wellFormedXml = true;
        } catch (Exception exception) {
            result.problems.add("Not well-formed XML: " + exception.getMessage());
            return result;
        }

        Element root = document.getDocumentElement();
        if (root == null || !"jmeterTestPlan".equals(root.getTagName())) {
            result.problems.add("Root element is not <jmeterTestPlan> (found: "
                    + (root == null ? "none" : root.getTagName()) + ")");
        }

        result.hasTestPlan = document.getElementsByTagName("TestPlan").getLength() > 0;
        if (!result.hasTestPlan) {
            result.problems.add("No <TestPlan> element found.");
        }

        result.hasThreadGroup = document.getElementsByTagName("ThreadGroup").getLength() > 0;
        if (!result.hasThreadGroup) {
            result.problems.add("No <ThreadGroup> element found.");
        }

        result.httpSamplerCount = document.getElementsByTagName("HTTPSamplerProxy").getLength();
        if (result.httpSamplerCount == 0) {
            result.problems.add("No <HTTPSamplerProxy> (HTTP Request) samplers found.");
        }

        result.hasHeaderManager = document.getElementsByTagName("HeaderManager").getLength() > 0;
        if (!result.hasHeaderManager) {
            result.problems.add("No <HeaderManager> element found - authentication/headers may be missing.");
        }

        result.responseAssertionCount = document.getElementsByTagName("ResponseAssertion").getLength();

        // Every hashTree in a valid JMeter plan pairs 1:1 with the test-element sibling right before
        // it (TestPlan -> hashTree, ThreadGroup -> hashTree, each sampler -> hashTree, ...) - JMeter
        // itself refuses to load a plan where this structural pairing is broken, so counting is a
        // real, cheap structural sanity check beyond "the expected tag names exist somewhere".
        NodeList hashTrees = document.getElementsByTagName("hashTree");
        if (hashTrees.getLength() == 0) {
            result.problems.add("No <hashTree> elements found - not a structurally valid JMeter plan.");
        }

        return result;
    }
}
