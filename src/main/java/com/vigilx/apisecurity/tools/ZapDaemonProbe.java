package com.vigilx.apisecurity.tools;

import com.vigilx.apisecurity.zap.ZapClient;
import com.vigilx.apisecurity.zap.ZapDaemonManager;

/**
 * Throwaway probe: confirms the local OWASP ZAP install actually starts and answers its JSON API -
 * independent of this app's own login (which may be locked/unavailable), since
 * {@link com.vigilx.apisecurity.zap.ZapScanRunner#run} would otherwise fail at the login step before
 * ever reaching ZAP itself. Starts the daemon if needed, reads its version, then stops it again if
 * this probe is the one that started it. Never accesses any live application URL.
 */
public final class ZapDaemonProbe {
    private ZapDaemonProbe() {
    }

    public static void main(String[] args) {
        ZapDaemonManager daemon = new ZapDaemonManager();
        try {
            boolean up = daemon.ensureRunning();
            System.out.println("PROBE: ZAP daemon available = " + up);
            if (up) {
                ZapClient client = daemon.client();
                System.out.println("PROBE: ZAP version = " + client.version());
                System.out.println("PROBE: recordsToScan (passive queue) = " + client.recordsToScan());
            }
            System.out.println("PROBE: RESULT = " + (up ? "PASS" : "FAIL"));
        } finally {
            daemon.stopIfStartedByUs();
        }
    }
}
