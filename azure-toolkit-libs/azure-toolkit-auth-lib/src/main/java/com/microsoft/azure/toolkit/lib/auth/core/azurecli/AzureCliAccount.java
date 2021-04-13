/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth.core.azurecli;

import com.azure.identity.implementation.util.IdentityConstants;
import com.microsoft.azure.toolkit.lib.auth.Account;
import com.microsoft.azure.toolkit.lib.auth.TokenCredentialManager;
import com.microsoft.azure.toolkit.lib.auth.exception.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.auth.model.AuthType;
import com.microsoft.azure.toolkit.lib.auth.model.AzureCliSubscription;
import com.microsoft.azure.toolkit.lib.auth.util.AzureCliUtils;
import com.microsoft.azure.toolkit.lib.common.model.Subscription;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

import static com.microsoft.azure.toolkit.lib.common.utils.Utils.distinctByKey;

public class AzureCliAccount extends Account {
    @Override
    public AuthType getAuthType() {
        return AuthType.AZURE_CLI;
    }

    protected Mono<Boolean> preLoginCheck() {
        return Mono.fromCallable(() -> {
            AzureCliUtils.ensureMinimumCliVersion();
            AzureCliUtils.executeAzCommandJson("az account get-access-token --output json");
            return true;
        });
    }

    protected Mono<TokenCredentialManager> createTokenCredentialManager() {
        List<AzureCliSubscription> subscriptions = AzureCliUtils.listSubscriptions();
        if (subscriptions.isEmpty()) {
            throw new AzureToolkitAuthenticationException("Cannot find any subscriptions in current account.");
        }

        AzureCliSubscription defaultSubscription = subscriptions.stream()
                .filter(AzureCliSubscription::isSelected).findFirst().orElse(subscriptions.get(0));

        this.entity.setEmail(defaultSubscription.getEmail());

        subscriptions = subscriptions.stream().filter(s -> StringUtils.equals(this.entity.getEmail(), s.getEmail()))
                .collect(Collectors.toList());

        // use the tenant who has one or more subscriptions
        this.entity.setTenantIds(subscriptions.stream().map(Subscription::getTenantId).distinct().collect(Collectors.toList()));

        this.entity.setSubscriptions(subscriptions.stream()
                .filter(distinctByKey(t -> StringUtils.lowerCase(t.getId()))).map(AzureCliAccount::toSubscription).collect(Collectors.toList()));

        // set initial selection of subscriptions
        this.entity.setSelectedSubscriptionIds(subscriptions.stream().filter(Subscription::isSelected)
                .map(Subscription::getId).distinct().collect(Collectors.toList()));

        return Mono.just(new AzureCliTokenCredentialManager(defaultSubscription.getEnvironment()));
    }

    private static Subscription toSubscription(AzureCliSubscription s) {
        Subscription subscription = new Subscription();
        subscription.setId(s.getId());
        subscription.setName(s.getName());
        subscription.setSelected(s.isSelected());
        subscription.setTenantId(s.getTenantId());
        return subscription;
    }

    @Override
    public String getClientId() {
        return IdentityConstants.DEVELOPER_SINGLE_SIGN_ON_ID;
    }
}
