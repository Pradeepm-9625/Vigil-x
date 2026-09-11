package com.vigilx.listeners;

import org.apache.logging.log4j.Logger;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import com.vigilx.utils.LoggerUtils;

/**
 * TestNG Listener
 *
 * Handles test execution events.
 */
public class TestListener implements ITestListener {

    private static final Logger LOGGER = LoggerUtils.getLogger(TestListener.class);

    @Override
    public void onStart(ITestContext context) {
        LOGGER.info("TEST SUITE STARTED | suite={}", context.getName());
    }

    @Override
    public void onFinish(ITestContext context) {
        LOGGER.info("TEST SUITE COMPLETED | suite={} | passed={} | failed={} | skipped={}",
                context.getName(),
                context.getPassedTests().size(),
                context.getFailedTests().size(),
                context.getSkippedTests().size());
    }

    @Override
    public void onTestStart(ITestResult result) {
        result.setAttribute("startTime", System.currentTimeMillis());
        LOGGER.info("TEST STARTED | class={} | method={} | description={}",
                result.getTestClass().getName(),
                result.getMethod().getMethodName(),
                result.getMethod().getDescription());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        LOGGER.info("TEST PASSED | class={} | method={} | durationMs={}",
                result.getTestClass().getName(), result.getMethod().getMethodName(), durationMs(result));
    }

    @Override
    public void onTestFailure(ITestResult result) {
        LOGGER.error("TEST FAILED | class={} | method={} | durationMs={}",
                result.getTestClass().getName(), result.getMethod().getMethodName(), durationMs(result),
                result.getThrowable());
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        LOGGER.warn("TEST SKIPPED | class={} | method={} | durationMs={}",
                result.getTestClass().getName(), result.getMethod().getMethodName(), durationMs(result));
    }

    private long durationMs(ITestResult result) {
        Object startTime = result.getAttribute("startTime");
        return startTime instanceof Long
                ? System.currentTimeMillis() - (Long) startTime
                : result.getEndMillis() - result.getStartMillis();
    }
}