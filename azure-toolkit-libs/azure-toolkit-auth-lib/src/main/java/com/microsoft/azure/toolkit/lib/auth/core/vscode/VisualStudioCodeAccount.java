/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.core.vscode;

import com.azure.core.management.AzureEnvironment;
import com.microsoft.applicationinsights.core.dependencies.apachecommons.lang3.ObjectUtils;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.auth.Account;
import com.microsoft.azure.toolkit.lib.auth.AzureCloud;
import com.microsoft.azure.toolkit.lib.auth.TokenCredentialManager;
import com.microsoft.azure.toolkit.lib.auth.RefreshTokenTokenCredentialManager;
import com.microsoft.azure.toolkit.lib.auth.model.AuthType;
import com.microsoft.azure.toolkit.lib.auth.util.AzureEnvironmentUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class VisualStudioCodeAccount extends Account {
    private static final String VSCODE_CLIENT_ID = "aebc6443-996d-45c2-90f0-388ff96faa56";

    private String refreshToken;
    private String vscodeCloudName;
    private Map<String, String> vscodeUserSettings;

    @Override
    public AuthType getAuthType() {
        return AuthType.VSCODE;
    }

    @Override
    protected String getClientId() {
        return VSCODE_CLIENT_ID;
    }

    protected Mono<Boolean> preLoginCheck() {
        return Mono.fromCallable(() -> {
            VisualStudioCacheAccessor accessor = new VisualStudioCacheAccessor();
            vscodeUserSettings = accessor.getUserSettingsDetails();
            vscodeCloudName = vscodeUserSettings.get("cloud");
            refreshToken = accessor.getCredentials("VS Code Azure", vscodeCloudName);
            if (vscodeUserSettings.containsKey("filter")) {
                final List<String> filteredSubscriptions = Arrays.asList(StringUtils.split(vscodeUserSettings.get("filter"), ","));
                this.entity.setSelectedSubscriptionIds(filteredSubscriptions);
            }

            return StringUtils.isNotBlank(refreshToken);
        });
    }

    protected Mono<TokenCredentialManager> createTokenCredentialManager() {
        AzureEnvironment env = ObjectUtils.firstNonNull(StringUtils.isNotBlank(vscodeCloudName) ?
                        AzureEnvironmentUtils.stringToAzureEnvironment(vscodeCloudName) : null,
                Azure.az(AzureCloud.class).getOrDefault());
        return RefreshTokenTokenCredentialManager.createTokenCredentialManager(env, getClientId(), refreshToken);
    }
}
