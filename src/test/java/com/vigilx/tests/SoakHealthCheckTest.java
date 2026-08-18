package com.vigilx.tests;

import com.vigilx.soak.SoakHealthCheckRunner;
import com.vigilx.soak.SoakTestConfig;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Opt-in entry point: mvn test -Dtest=SoakHealthCheckTest -Dsoak.enabled=true */
public class SoakHealthCheckTest {
    @Test
    public void runConfiguredHealthCheck() {
        if (!SoakTestConfig.load().enabled()) throw new SkipException("Set soak.enabled=true to run soak checks");
        Assert.assertEquals(SoakHealthCheckRunner.runOnce().overall, "PASS", "See target/soak-results for evidence.");
    }
}
