/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.tools.auth.maven;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.tools.auth.AuthHelper;
import com.microsoft.azure.tools.auth.exception.AzureLoginException;
import com.microsoft.azure.tools.auth.exception.LoginFailureException;
import com.microsoft.azure.tools.auth.model.AuthType;
import com.microsoft.azure.tools.auth.model.AzureCredentialWrapper;
import com.microsoft.azure.tools.auth.model.MavenAuthConfiguration;
import com.microsoft.azure.tools.auth.core.*;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import rx.Single;

import java.util.Objects;

public class MavenAuthHelper {
    public static Single<AzureCredentialWrapper> getAzureToken(MavenAuthConfiguration configuration, MavenSession session,
                                                       SettingsDecrypter settingsDecrypter) throws AzureLoginException {
        return getAzureTokenInner(configuration, session, settingsDecrypter).doOnSuccess(wrapper -> {
            if (Objects.nonNull(wrapper) && Objects.nonNull(wrapper.getEnv()) && !wrapper.getEnv().equals(AzureEnvironment.AZURE)) {
                AuthHelper.setupAzureEnvironment(wrapper.getEnv());
            }
        });
    }

    private static Single<AzureCredentialWrapper> getAzureTokenInner(MavenAuthConfiguration mavenAuthConfiguration, MavenSession session,
                                                                     SettingsDecrypter settingsDecrypter) throws AzureLoginException {
        MavenAuthConfiguration configuration = mavenAuthConfiguration;
        if (configuration == null) {
            configuration = new MavenAuthConfiguration();
        }
        AzureEnvironment env = AuthHelper.parseAzureEnvironment(configuration.getEnvironment());
        if (env == null) {
            env = AzureEnvironment.AZURE;
        }
        ChainedCredentialRetriever retriever = new ChainedCredentialRetriever();

        AuthType authType = AuthType.parseAuthType(configuration.getAuthType());
        if (authType.equals(AuthType.AUTO)) {
            // for AUTO mode:
            // Please be aware that if there are incomplete <auth> configurations, it will stop here reporting an user error
            // in other words, in AUTO mode, if you want to use other auth method like vscode, azure cli, the maven auth configuration
            // must be empty.
            AzureCredentialWrapper credentialFromSP = ServicePrincipalLoginHelper.login(configuration, session, settingsDecrypter).toBlocking().value();
            if (credentialFromSP != null) {
                return Single.just(credentialFromSP);
            }

            retriever.addRetriever(new ManagedIdentityCredentialRetriever()::retrieve);
            retriever.addRetriever(new AzureCliCredentialRetriever()::retrieve);
            retriever.addRetriever(new VsCodeCredentialRetriever()::retrieve);
            retriever.addRetriever(new OAuthCredentialRetriever(env)::retrieve);
            retriever.addRetriever(new DeviceCodeCredentialRetriever(env)::retrieve);
        } else {
            MavenAuthConfiguration finalConfiguration = configuration;
            // for specific auth type:
            switch (authType) {
                case SERVICE_PRINCIPAL: // will get configuration from maven settings or maven configuration
                    retriever.addRetriever(() -> ServicePrincipalLoginHelper.login(finalConfiguration, session, settingsDecrypter));
                    break;
                case MANAGED_IDENTITY:
                    retriever.addRetriever(new ManagedIdentityCredentialRetriever()::retrieve);
                    break;
                case AZURE_CLI:
                    retriever.addRetriever(new AzureCliCredentialRetriever()::retrieve);
                    break;
                case DEVICE_CODE:
                    retriever.addRetriever(new OAuthCredentialRetriever(env)::retrieve);
                    break;
                case OAUTH2:
                    retriever.addRetriever(new DeviceCodeCredentialRetriever(env)::retrieve);
                    break;
                default:
                    return Single.error(new UnsupportedOperationException(String.format("authType '%s' not supported.", authType)));
            }
            // add last pure error virtual retriever to show reasonable error instead of showing the last error
            retriever.addRetriever(() -> Single.error(new LoginFailureException(String.format("Cannot get credentials from authType '%s'.", authType))));
        }
        return retriever.retrieve();
    }
}
