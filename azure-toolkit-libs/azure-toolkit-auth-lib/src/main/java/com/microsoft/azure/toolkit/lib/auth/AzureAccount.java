/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.auth;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.identity.SharedTokenCacheCredential;
import com.azure.identity.SharedTokenCacheCredentialBuilder;
import com.azure.identity.TokenCachePersistenceOptions;
import com.google.common.base.Preconditions;
import com.microsoft.azure.toolkit.lib.AzureService;
import com.microsoft.azure.toolkit.lib.account.IAzureAccount;
import com.microsoft.azure.toolkit.lib.auth.core.azurecli.AzureCliAccount;
import com.microsoft.azure.toolkit.lib.auth.core.devicecode.DeviceCodeAccount;
import com.microsoft.azure.toolkit.lib.auth.core.oauth.OAuthAccount;
import com.microsoft.azure.toolkit.lib.auth.core.serviceprincipal.ServicePrincipalAccount;
import com.microsoft.azure.toolkit.lib.auth.exception.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.auth.exception.LoginFailureException;
import com.microsoft.azure.toolkit.lib.auth.model.AccountEntity;
import com.microsoft.azure.toolkit.lib.auth.model.AuthConfiguration;
import com.microsoft.azure.toolkit.lib.auth.model.AuthType;
import com.microsoft.azure.toolkit.lib.auth.util.AzureEnvironmentUtils;
import io.jsonwebtoken.lang.Collections;
import lombok.AccessLevel;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AzureAccount implements AzureService, IAzureAccount {

    @Setter(AccessLevel.PRIVATE)
    private Account account;

    /**
     * @return the current account
     * @throws AzureToolkitAuthenticationException if not initialized
     */
    public Account account() throws AzureToolkitAuthenticationException {
        return Optional.ofNullable(this.account)
                .orElseThrow(() -> new AzureToolkitAuthenticationException("Please signed in first."));
    }

    public Account account(@Nonnull AccountEntity accountEntity) {
        return restoreLogin(accountEntity).block();
    }

    public List<Account> accounts() {
        return Flux.fromIterable(buildAccountMap().values()).map(Supplier::get).collectList().block();
    }

    public AzureAccount login(@Nonnull AuthType type) {
        return login(type, false);
    }

    public AzureAccount login(@Nonnull Account targetAccount) {
        return login(targetAccount, false);
    }

    public AzureAccount login(@Nonnull AuthConfiguration auth) {
        return login(auth, false);
    }

    public AzureAccount login(@Nonnull AuthType type, boolean enablePersistence) {
        return blockMonoAndReturnThis(loginAsync(type, enablePersistence));
    }

    public AzureAccount login(@Nonnull Account targetAccount, boolean enablePersistence) {
        return blockMonoAndReturnThis(loginAsync(targetAccount, enablePersistence));
    }

    public AzureAccount login(@Nonnull AuthConfiguration auth, boolean enablePersistence) {
        return blockMonoAndReturnThis(loginAsync(auth, enablePersistence));
    }

    public void logout() {
        if (this.account != null) {
            Account tempAccount = this.account;
            this.account = null;
            tempAccount.logout();
        }
    }

    private Mono<Account> restoreLogin(@Nonnull AccountEntity accountEntity) {
        Preconditions.checkNotNull(accountEntity.getEnvironment(), "Azure environment for account entity is required.");
        Preconditions.checkNotNull(accountEntity.getType(), "Auth type for account entity is required.");
        Preconditions.checkArgument(!Collections.isEmpty(accountEntity.getTenantIds()),
                "At least one tenant id is required.");
        Account target = null;
        if (Arrays.asList(AuthType.DEVICE_CODE, AuthType.OAUTH2).contains(accountEntity.getType())) {
            AzureEnvironmentUtils.setupAzureEnvironment(accountEntity.getEnvironment());
            SharedTokenCacheCredentialBuilder builder = new SharedTokenCacheCredentialBuilder();
            SharedTokenCacheCredential credential = builder
                    .tokenCachePersistenceOptions(new TokenCachePersistenceOptions().setName(Account.TOOLKIT_TOKEN_CACHE_NAME))
                    .username(accountEntity.getEmail()).tenantId(accountEntity.getTenantIds().get(0)).clientId(accountEntity.getClientId())
                    .build();

            target = new SimpleAccount(accountEntity, credential);
        } else if (Arrays.asList(AuthType.VSCODE, AuthType.AZURE_CLI).contains(accountEntity.getType())) {
            target = buildAccountMap().get(accountEntity.getType()).get();
        } else {
            return Mono.error(new AzureToolkitAuthenticationException(String.format("Cannot restore login for auth type '%s'.", accountEntity.getType())));
        }
        return target.login().map(ac -> {
            if (ac.getEnvironment() != accountEntity.getEnvironment()) {
                throw new AzureToolkitAuthenticationException(
                        String.format("you have changed the azure cloud to '%s' for auth type: '%s' since last time you signed in.",
                                AzureEnvironmentUtils.getCloudNameForAzureCli(ac.getEnvironment()), accountEntity.getType()));
            }
            if (!StringUtils.equalsIgnoreCase(ac.entity.getEmail(), accountEntity.getEmail())) {
                throw new AzureToolkitAuthenticationException(
                        String.format("you have changed the account from '%s' to '%s' since last time you signed in.",
                                accountEntity.getEmail(), ac.entity.getEmail()));
            }
            return ac;
        }).doOnSuccess(this::setAccount);
    }

    static class SimpleAccount extends Account {
        private final TokenCredential credential;

        public SimpleAccount(@Nonnull AccountEntity accountEntity, @Nonnull TokenCredential credential) {
            Preconditions.checkNotNull(accountEntity.getEnvironment(), "Azure environment for account entity is required.");
            Preconditions.checkNotNull(accountEntity.getType(), "Auth type for account entity is required.");
            Preconditions.checkArgument(!Collections.isEmpty(accountEntity.getTenantIds()),
                    "At least one tenant id is required.");
            this.entity = new AccountEntity();
            this.entity.setClientId(accountEntity.getClientId());
            this.entity.setType(accountEntity.getType());
            this.entity.setEmail(accountEntity.getEmail());
            this.entity.setEnvironment(accountEntity.getEnvironment());
            this.credential = credential;
        }
        protected Mono<TokenCredentialManager> createTokenCredentialManager() {
            AzureEnvironment env = this.entity.getEnvironment();
            return RefreshTokenTokenCredentialManager.createTokenCredentialManager(env, getClientId(), createCredential());
        }

        private TokenCredential createCredential() {
            return credential;
        }

        @Override
        public AuthType getAuthType() {
            return this.entity.getType();
        }

        @Override
        protected String getClientId() {
            return this.entity.getClientId();
        }

        @Override
        protected Mono<Boolean> preLoginCheck() {
            return Mono.just(true);
        }
    }

    public Mono<Account> loginAsync(AuthType type, boolean enablePersistence) {
        Objects.requireNonNull(type, "Please specify auth type in auth configuration.");
        AuthConfiguration auth = new AuthConfiguration();
        auth.setType(type);
        return loginAsync(auth, enablePersistence);
    }

    public Mono<Account> loginAsync(@Nonnull AuthConfiguration auth, boolean enablePersistence) {
        Objects.requireNonNull(auth, "Auth configuration is required for login.");
        Objects.requireNonNull(auth.getType(), "Auth type is required for login.");
        Preconditions.checkArgument(auth.getType() != AuthType.AUTO, "Auth type 'auto' is illegal for login.");

        AuthType type = auth.getType();
        final Account targetAccount;
        if (auth.getType() == AuthType.SERVICE_PRINCIPAL) {
            targetAccount = new ServicePrincipalAccount(auth);
        } else {
            Map<AuthType, Supplier<Account>> accountByType = buildAccountMap();
            if (!accountByType.containsKey(type)) {
                return Mono.error(new LoginFailureException(String.format("Unsupported auth type '%s', supported values are: %s.",
                        type, accountByType.keySet().stream().map(Object::toString).map(StringUtils::lowerCase).collect(Collectors.joining(", ")))));
            }
            targetAccount = accountByType.get(type).get();
        }

        return loginAsync(targetAccount, enablePersistence).doOnSuccess(ignore -> checkEnv(targetAccount, auth.getEnvironment()));
    }

    public Mono<Account> loginAsync(Account targetAccount, boolean enablePersistence) {
        Objects.requireNonNull(targetAccount, "Please specify account to login.");
        targetAccount.setEnablePersistence(enablePersistence);
        return targetAccount.login().doOnSuccess(this::setAccount);
    }

    private AzureAccount blockMonoAndReturnThis(Mono<Account> mono) {
        try {
            mono.block();
            return this;
        } catch (Throwable ex) {
            throw new AzureToolkitAuthenticationException("Cannot login due to error: " + ex.getMessage());
        }
    }

    private static void checkEnv(Account ac, AzureEnvironment env) {
        if (env != null && ac.getEnvironment() != null && ac.getEnvironment() != env && ac.isAvailable()) {
            String expectedEnv = AzureEnvironmentUtils.getCloudNameForAzureCli(env);
            String realEnv = AzureEnvironmentUtils.getCloudNameForAzureCli(ac.getEnvironment());

            // conflicting configuration of azure environment
            switch (ac.getAuthType()) {
                case AZURE_CLI:
                    throw new AzureToolkitAuthenticationException(
                            String.format("The azure cloud from azure cli '%s' doesn't match with your auth configuration, " +
                                            "you can change it by executing 'az cloud set --name=%s' command to change the cloud in azure cli.",
                                    realEnv,
                                    expectedEnv));

                case AZURE_AUTH_MAVEN_PLUGIN:
                    throw new AzureToolkitAuthenticationException(
                            String.format("The azure cloud from maven login '%s' doesn't match with your auth configuration, " +
                                            "please switch to other auth method for '%s' environment.",
                                    realEnv,
                                    expectedEnv));
                case VSCODE:
                    throw new AzureToolkitAuthenticationException(
                            String.format("The azure cloud from vscode '%s' doesn't match with your auth configuration: %s, " +
                                            "you can change it by pressing F1 in VSCode and find \">azure: sign in to Azure Cloud\" command " +
                                            "to change azure cloud in vscode.",
                                    realEnv,
                                    expectedEnv));
                default: // empty

            }
        }
    }

    private static Map<AuthType, Supplier<Account>> buildAccountMap() {
        Map<AuthType, Supplier<Account>> map = new LinkedHashMap<>();
        // SP is not there since it requires special constructor argument and it is special(it requires complex auth configuration)
        map.put(AuthType.AZURE_CLI, AzureCliAccount::new);
        map.put(AuthType.OAUTH2, OAuthAccount::new);
        map.put(AuthType.DEVICE_CODE, DeviceCodeAccount::new);
        return map;
    }
}
