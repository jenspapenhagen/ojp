package org.openjproxy.grpc.server.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PropertyPlaceholderResolver.
 */
class PropertyPlaceholderResolverTest {
    
    @BeforeEach
    void setUp() {
        // Clear any test properties before each test
        System.clearProperty("ojp.server.sslrootcert");
        System.clearProperty("ojp.server.sslcert");
        System.clearProperty("ojp.server.sslkey");
        System.clearProperty("test.property");
    }
    
    @AfterEach
    void tearDown() {
        // Clean up test properties after each test
        System.clearProperty("ojp.server.sslrootcert");
        System.clearProperty("ojp.server.sslcert");
        System.clearProperty("ojp.server.sslkey");
        System.clearProperty("test.property");
    }
    
    @Test
    void testResolveSinglePlaceholder() {
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/ca-cert.pem");
        
        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";
        String expected = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/ca-cert.pem";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveMultiplePlaceholders() {
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/ca-cert.pem");
        System.setProperty("ojp.server.sslcert", "/etc/ojp/certs/client-cert.pem");
        System.setProperty("ojp.server.sslkey", "/etc/ojp/certs/client-key.pem");
        
        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}&sslcert=${ojp.server.sslcert}&sslkey=${ojp.server.sslkey}";
        String expected = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/ca-cert.pem&sslcert=/etc/ojp/certs/client-cert.pem&sslkey=/etc/ojp/certs/client-key.pem";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveWithSpecialCharactersInPath() {
        // Test paths with special characters that might need escaping
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/$special/ca-cert.pem");
        
        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";
        String expected = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/$special/ca-cert.pem";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveWithBackslashesInPath() {
        // Test Windows-style paths with backslashes
        System.setProperty("ojp.server.sslrootcert", "C:\\ojp\\certs\\ca-cert.pem");
        
        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";
        String expected = "jdbc:postgresql://host:5432/db?sslrootcert=C:\\ojp\\certs\\ca-cert.pem";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveMissingPlaceholderThrowsException() {
        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            PropertyPlaceholderResolver.resolvePlaceholders(input);
        });
        
        assertTrue(exception.getMessage().contains("ojp.server.sslrootcert"));
        assertTrue(exception.getMessage().contains("OJP_SERVER_SSLROOTCERT"));
    }
    
    @Test
    void testResolveNoPlaceholders() {
        String input = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/ca-cert.pem";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(input, result);
    }
    
    @Test
    void testResolveNullInput() {
        String result = PropertyPlaceholderResolver.resolvePlaceholders(null);
        assertNull(result);
    }
    
    @Test
    void testResolveEmptyString() {
        String result = PropertyPlaceholderResolver.resolvePlaceholders("");
        assertEquals("", result);
    }
    
    @Test
    void testContainsPlaceholders() {
        assertTrue(PropertyPlaceholderResolver.containsPlaceholders("${ojp.server.sslrootcert}"));
        assertTrue(PropertyPlaceholderResolver.containsPlaceholders("text${placeholder}more"));
        assertFalse(PropertyPlaceholderResolver.containsPlaceholders("no placeholders here"));
        assertFalse(PropertyPlaceholderResolver.containsPlaceholders(null));
        assertFalse(PropertyPlaceholderResolver.containsPlaceholders(""));
    }
    
    @Test
    void testResolvePostgreSQLUrlWithSSL() {
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/ca-cert.pem");
        
        String input = "jdbc:postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=${ojp.server.sslrootcert}";
        String expected = "jdbc:postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=/etc/ojp/certs/ca-cert.pem";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveMySQLUrlWithSSL() {
        System.setProperty("ojp.server.trustCertificateKeyStoreUrl", "file:///etc/ojp/certs/truststore.jks");
        System.setProperty("ojp.server.clientCertificateKeyStoreUrl", "file:///etc/ojp/certs/keystore.jks");
        
        String input = "jdbc:mysql://host:3306/db?useSSL=true&trustCertificateKeyStoreUrl=${ojp.server.trustCertificateKeyStoreUrl}&clientCertificateKeyStoreUrl=${ojp.server.clientCertificateKeyStoreUrl}";
        String expected = "jdbc:mysql://host:3306/db?useSSL=true&trustCertificateKeyStoreUrl=file:///etc/ojp/certs/truststore.jks&clientCertificateKeyStoreUrl=file:///etc/ojp/certs/keystore.jks";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveOracleUrlWithWallet() {
        System.setProperty("ojp.server.oracle.wallet.location", "/etc/ojp/wallet");
        
        String input = "jdbc:oracle:thin:@host:1521/service?oracle.net.wallet_location=${ojp.server.oracle.wallet.location}";
        String expected = "jdbc:oracle:thin:@host:1521/service?oracle.net.wallet_location=/etc/ojp/wallet";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveSQLServerUrlWithTrustStore() {
        System.setProperty("ojp.server.trustStore", "/etc/ojp/certs/truststore.jks");
        System.setProperty("ojp.server.trustStorePassword", "changeit");
        
        String input = "jdbc:sqlserver://host:1433;databaseName=mydb;encrypt=true;trustStore=${ojp.server.trustStore};trustStorePassword=${ojp.server.trustStorePassword}";
        String expected = "jdbc:sqlserver://host:1433;databaseName=mydb;encrypt=true;trustStore=/etc/ojp/certs/truststore.jks;trustStorePassword=changeit";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveDB2UrlWithSSL() {
        System.setProperty("ojp.server.sslTrustStoreLocation", "/etc/ojp/certs/truststore.jks");
        
        String input = "jdbc:db2://host:50001/mydb:sslConnection=true;sslTrustStoreLocation=${ojp.server.sslTrustStoreLocation};";
        String expected = "jdbc:db2://host:50001/mydb:sslConnection=true;sslTrustStoreLocation=/etc/ojp/certs/truststore.jks;";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolvePlaceholderAtStartOfString() {
        System.setProperty("test.property", "value");
        
        String input = "${test.property}-rest-of-string";
        String expected = "value-rest-of-string";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolvePlaceholderAtEndOfString() {
        System.setProperty("test.property", "value");
        
        String input = "start-of-string-${test.property}";
        String expected = "start-of-string-value";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testResolveMultipleSamePlaceholders() {
        System.setProperty("test.property", "value");
        
        String input = "${test.property} and ${test.property} again";
        String expected = "value and value again";
        String result = PropertyPlaceholderResolver.resolvePlaceholders(input);
        
        assertEquals(expected, result);
    }
}
