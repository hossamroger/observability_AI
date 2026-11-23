package ae.sharjah.digital.observability.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfiguration {
    @Bean
    @Primary
    public RestTemplate monitoringRestTemplate() {
        return createClient(new SimpleClientHttpRequestFactory());
    }

    @Bean
    public RestTemplate elkRestTemplate(
            @Value("${monitoring.elk.ssl-verification-enabled:true}") boolean verify,
            Environment environment) throws GeneralSecurityException {
        if (verify) return createClient(new SimpleClientHttpRequestFactory());
        if (!environment.acceptsProfiles(Profiles.of("local"))
                || environment.acceptsProfiles(Profiles.of("weblogic"))) {
            throw new IllegalStateException("ELK insecure TLS is permitted only in local development");
        }
        LoggerFactory.getLogger(HttpClientConfiguration.class)
                .warn("event=elk_tls_verification_disabled scope=local_only");
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        } }, null);
        return createClient(new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method)
                    throws IOException {
                super.prepareConnection(connection, method);
                if (connection instanceof HttpsURLConnection) {
                    HttpsURLConnection https = (HttpsURLConnection) connection;
                    https.setSSLSocketFactory(context.getSocketFactory());
                    https.setHostnameVerifier((hostname, session) -> true);
                }
            }
        });
    }

    private RestTemplate createClient(SimpleClientHttpRequestFactory factory) {
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(12000);
        return new RestTemplate(factory);
    }
}
