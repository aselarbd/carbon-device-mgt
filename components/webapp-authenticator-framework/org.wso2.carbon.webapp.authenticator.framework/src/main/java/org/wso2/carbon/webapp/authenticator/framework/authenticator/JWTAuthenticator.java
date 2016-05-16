/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.webapp.authenticator.framework.authenticator;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.service.TenantRegistryLoader;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.carbon.webapp.authenticator.framework.AuthenticationInfo;
import org.wso2.carbon.webapp.authenticator.framework.AuthenticatorFrameworkDataHolder;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

/**
 * This authenticator authenticates HTTP requests using JWT header.
 */
public class JWTAuthenticator implements WebappAuthenticator {

    private static final Log log = LogFactory.getLog(JWTAuthenticator.class);
    private static final String SIGNED_JWT_AUTH_USERNAME = "http://wso2.org/claims/enduser";
    private static final String SIGNED_JWT_AUTH_TENANT_ID = "http://wso2.org/claims/enduserTenantId";
    private static final String JWT_AUTHENTICATOR = "JWT";
    private static final String JWT_ASSERTION_HEADER = "X-JWT-Assertion";
    private static final Map<String, PublicKey> publicKeyHolder = new HashMap<>();
    @Override
    public void init() {

    }

    @Override
    public boolean canHandle(Request request) {
        String authorizationHeader = request.getHeader(JWTAuthenticator.JWT_ASSERTION_HEADER);
        if ((authorizationHeader != null) && !authorizationHeader.isEmpty()) {
            return true;
        }
        return false;
    }

    @Override
    public AuthenticationInfo authenticate(Request request, Response response) {
        String requestUri = request.getRequestURI();
        AuthenticationInfo authenticationInfo = new AuthenticationInfo();
        if (requestUri == null || "".equals(requestUri)) {
            authenticationInfo.setStatus(Status.CONTINUE);
        }
        StringTokenizer tokenizer = new StringTokenizer(requestUri, "/");
        String context = tokenizer.nextToken();
        if (context == null || "".equals(context)) {
            authenticationInfo.setStatus(Status.CONTINUE);
        }

        try {
            String authorizationHeader = request.getHeader(JWT_ASSERTION_HEADER);
            SignedJWT jwsObject = SignedJWT.parse(authorizationHeader);
            String username = jwsObject.getJWTClaimsSet().getStringClaim(SIGNED_JWT_AUTH_USERNAME);
            String tenantDomain = MultitenantUtils.getTenantDomain(username);
            int tenantId = jwsObject.getJWTClaimsSet().getIntegerClaim(SIGNED_JWT_AUTH_TENANT_ID);
            PublicKey publicKey =  publicKeyHolder.get(tenantDomain);
            if (publicKey == null) {
                loadTenantRegistry(tenantId);
                KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(tenantId);
                publicKey = keyStoreManager.getDefaultPublicKey();
                publicKeyHolder.put(tenantDomain, publicKey);
            }

            //Get the filesystem keystore default primary certificate
            JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) publicKey);
            if (jwsObject.verify(verifier)) {
                username = MultitenantUtils.getTenantAwareUsername(username);
                if (tenantId == -1) {
                    log.error("tenantDomain is not valid. username : " + username + ", tenantDomain " +
                            ": " + tenantDomain);
                } else {
                    UserStoreManager userStore = AuthenticatorFrameworkDataHolder.getInstance().getRealmService().
                            getTenantUserRealm(tenantId).getUserStoreManager();
                    if (userStore.isExistingUser(username)) {
                        authenticationInfo.setTenantId(tenantId);
                        authenticationInfo.setUsername(username);
                        authenticationInfo.setTenantDomain(tenantDomain);
                        authenticationInfo.setStatus(Status.CONTINUE);
                    }
                }
            } else {
                authenticationInfo.setStatus(Status.FAILURE);
            }
        } catch (UserStoreException e) {
            log.error("Error occurred while obtaining the user.", e);
        } catch (ParseException e) {
            log.error("Error occurred while parsing the JWT header.", e);
        } catch (JOSEException e) {
            log.error("Error occurred while verifying the JWT header.", e);
        } catch (Exception e) {
            log.error("Error occurred while verifying the JWT header.", e);
        }
        return authenticationInfo;
    }

    @Override
    public String getName() {
        return JWTAuthenticator.JWT_AUTHENTICATOR;
    }

    @Override
    public void setProperties(Properties properties) {

    }

    @Override
    public Properties getProperties() {
        return null;
    }

    @Override
    public String getProperty(String name) {
        return null;
    }

    private static void loadTenantRegistry(int tenantId) throws RegistryException {
        TenantRegistryLoader tenantRegistryLoader = AuthenticatorFrameworkDataHolder.getInstance().
                getTenantRegistryLoader();
        AuthenticatorFrameworkDataHolder.getInstance().getTenantIndexingLoader().loadTenantIndex(tenantId);
        tenantRegistryLoader.loadTenantRegistry(tenantId);
    }
}
