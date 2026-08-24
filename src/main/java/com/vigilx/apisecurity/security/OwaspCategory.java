package com.vigilx.apisecurity.security;

/** The OWASP API Security Top 10 (2023) categories this framework can speak to. */
public enum OwaspCategory {
    API1_BOLA("API1:2023", "Broken Object Level Authorization"),
    API2_BROKEN_AUTHENTICATION("API2:2023", "Broken Authentication"),
    API3_BROKEN_PROPERTY_AUTHORIZATION("API3:2023", "Broken Object Property Level Authorization"),
    API4_RESOURCE_CONSUMPTION("API4:2023", "Unrestricted Resource Consumption"),
    API5_BROKEN_FUNCTION_AUTHORIZATION("API5:2023", "Broken Function Level Authorization"),
    API6_SENSITIVE_BUSINESS_FLOWS("API6:2023", "Unrestricted Access to Sensitive Business Flows"),
    API7_SSRF("API7:2023", "Server Side Request Forgery"),
    API8_SECURITY_MISCONFIGURATION("API8:2023", "Security Misconfiguration"),
    API9_IMPROPER_INVENTORY("API9:2023", "Improper Inventory Management"),
    API10_UNSAFE_CONSUMPTION("API10:2023", "Unsafe Consumption of APIs");

    private final String id;
    private final String title;

    OwaspCategory(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String id() { return id; }
    public String title() { return title; }
}
