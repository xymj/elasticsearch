/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security.authz;

import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.concurrent.ThreadContext.StoredContext;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.license.MockLicenseState;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchContextMissingException;
import org.elasticsearch.search.internal.InternalScrollSearchRequest;
import org.elasticsearch.search.internal.LegacyReaderContext;
import org.elasticsearch.search.internal.ShardSearchContextId;
import org.elasticsearch.search.internal.ShardSearchRequest;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequest.Empty;
import org.elasticsearch.xpack.core.security.SecurityContext;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.Authentication.RealmRef;
import org.elasticsearch.xpack.core.security.authc.AuthenticationField;
import org.elasticsearch.xpack.core.security.authz.AuthorizationEngine.AuthorizationInfo;
import org.elasticsearch.xpack.core.security.authz.AuthorizationServiceField;
import org.elasticsearch.xpack.core.security.authz.accesscontrol.IndicesAccessControl;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.audit.AuditTrail;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.junit.Before;

import java.util.Collections;
import java.util.List;

import static org.elasticsearch.xpack.core.security.authz.AuthorizationServiceField.AUTHORIZATION_INFO_KEY;
import static org.elasticsearch.xpack.core.security.authz.AuthorizationServiceField.ORIGINATING_ACTION_KEY;
import static org.elasticsearch.xpack.security.audit.logfile.LoggingAuditTrail.PRINCIPAL_ROLES_FIELD_NAME;
import static org.elasticsearch.xpack.security.authz.AuthorizationServiceTests.authzInfoRoles;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class SecuritySearchOperationListenerTests extends ESSingleNodeTestCase {
    private IndexService indexService;
    private IndexShard shard;

    @Before
    public void setupShard() {
        indexService = createIndex("index");
        shard = indexService.getShard(0);
    }

    public void testOnNewContextSetsAuthentication() throws Exception {
        final ShardSearchRequest shardSearchRequest = mock(ShardSearchRequest.class);
        when(shardSearchRequest.scroll()).thenReturn(new Scroll(TimeValue.timeValueMinutes(between(1, 10))));
        try (
            LegacyReaderContext readerContext = new LegacyReaderContext(
                new ShardSearchContextId(UUIDs.randomBase64UUID(), 0L),
                indexService,
                shard,
                shard.acquireSearcherSupplier(),
                shardSearchRequest,
                Long.MAX_VALUE
            )
        ) {
            ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
            final SecurityContext securityContext = new SecurityContext(Settings.EMPTY, threadContext);
            AuditTrailService auditTrailService = mock(AuditTrailService.class);
            Authentication authentication = new Authentication(new User("test", "role"), new RealmRef("realm", "file", "node"), null);
            authentication.writeToContext(threadContext);
            IndicesAccessControl indicesAccessControl = mock(IndicesAccessControl.class);
            threadContext.putTransient(AuthorizationServiceField.INDICES_PERMISSIONS_KEY, indicesAccessControl);

            SecuritySearchOperationListener listener = new SecuritySearchOperationListener(securityContext, auditTrailService);
            listener.onNewScrollContext(readerContext);

            Authentication contextAuth = readerContext.getFromContext(AuthenticationField.AUTHENTICATION_KEY);
            assertEquals(authentication, contextAuth);
            assertThat(readerContext.getFromContext(AuthorizationServiceField.INDICES_PERMISSIONS_KEY), is(indicesAccessControl));

            verifyNoMoreInteractions(auditTrailService);
        }
    }

    public void testValidateSearchContext() throws Exception {
        final ShardSearchRequest shardSearchRequest = mock(ShardSearchRequest.class);
        when(shardSearchRequest.scroll()).thenReturn(new Scroll(TimeValue.timeValueMinutes(between(1, 10))));
        try (
            LegacyReaderContext readerContext = new LegacyReaderContext(
                new ShardSearchContextId(UUIDs.randomBase64UUID(), 0L),
                indexService,
                shard,
                shard.acquireSearcherSupplier(),
                shardSearchRequest,
                Long.MAX_VALUE
            )
        ) {
            readerContext.putInContext(
                AuthenticationField.AUTHENTICATION_KEY,
                new Authentication(new User("test", "role"), new RealmRef("realm", "file", "node"), null)
            );
            final IndicesAccessControl indicesAccessControl = mock(IndicesAccessControl.class);
            readerContext.putInContext(AuthorizationServiceField.INDICES_PERMISSIONS_KEY, indicesAccessControl);
            MockLicenseState licenseState = mock(MockLicenseState.class);
            when(licenseState.isAllowed(Security.AUDITING_FEATURE)).thenReturn(true);
            ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
            final SecurityContext securityContext = new SecurityContext(Settings.EMPTY, threadContext);
            AuditTrail auditTrail = mock(AuditTrail.class);
            AuditTrailService auditTrailService = new AuditTrailService(Collections.singletonList(auditTrail), licenseState);

            SecuritySearchOperationListener listener = new SecuritySearchOperationListener(securityContext, auditTrailService);
            try (StoredContext ignore = threadContext.newStoredContext(false)) {
                Authentication authentication = new Authentication(new User("test", "role"), new RealmRef("realm", "file", "node"), null);
                authentication.writeToContext(threadContext);
                listener.validateReaderContext(readerContext, Empty.INSTANCE);
                assertThat(threadContext.getTransient(AuthorizationServiceField.INDICES_PERMISSIONS_KEY), is(indicesAccessControl));
                verifyNoMoreInteractions(auditTrail);
            }

            try (StoredContext ignore = threadContext.newStoredContext(false)) {
                final String nodeName = randomAlphaOfLengthBetween(1, 8);
                final String realmName = randomAlphaOfLengthBetween(1, 16);
                Authentication authentication = new Authentication(
                    new User("test", "role"),
                    new RealmRef(realmName, "file", nodeName),
                    null
                );
                authentication.writeToContext(threadContext);
                listener.validateReaderContext(readerContext, Empty.INSTANCE);
                assertThat(threadContext.getTransient(AuthorizationServiceField.INDICES_PERMISSIONS_KEY), is(indicesAccessControl));
                verifyNoMoreInteractions(auditTrail);
            }

            try (StoredContext ignore = threadContext.newStoredContext(false)) {
                final String nodeName = randomBoolean() ? "node" : randomAlphaOfLengthBetween(1, 8);
                final String realmName = randomBoolean() ? "realm" : randomAlphaOfLengthBetween(1, 16);
                final String type = randomAlphaOfLengthBetween(5, 16);
                Authentication authentication = new Authentication(new User("test", "role"), new RealmRef(realmName, type, nodeName), null);
                authentication.writeToContext(threadContext);
                threadContext.putTransient(ORIGINATING_ACTION_KEY, "action");
                threadContext.putTransient(
                    AUTHORIZATION_INFO_KEY,
                    (AuthorizationInfo) () -> Collections.singletonMap(PRINCIPAL_ROLES_FIELD_NAME, authentication.getUser().roles())
                );
                final InternalScrollSearchRequest request = new InternalScrollSearchRequest();
                SearchContextMissingException expected = expectThrows(
                    SearchContextMissingException.class,
                    () -> listener.validateReaderContext(readerContext, request)
                );
                assertEquals(readerContext.id(), expected.contextId());
                assertThat(threadContext.getTransient(AuthorizationServiceField.INDICES_PERMISSIONS_KEY), nullValue());
                verify(auditTrail).accessDenied(
                    eq(null),
                    eq(authentication),
                    eq("action"),
                    eq(request),
                    authzInfoRoles(authentication.getUser().roles())
                );
            }

            // another user running as the original user
            try (StoredContext ignore = threadContext.newStoredContext(false)) {
                final String nodeName = randomBoolean() ? "node" : randomAlphaOfLengthBetween(1, 8);
                final String realmName = randomBoolean() ? "realm" : randomAlphaOfLengthBetween(1, 16);
                final String type = randomAlphaOfLengthBetween(5, 16);
                User user = new User(new User("test", "role"), new User("authenticated", "runas"));
                Authentication authentication = new Authentication(
                    user,
                    new RealmRef(realmName, type, nodeName),
                    new RealmRef(randomAlphaOfLengthBetween(1, 16), "file", nodeName)
                );
                authentication.writeToContext(threadContext);
                threadContext.putTransient(ORIGINATING_ACTION_KEY, "action");
                final InternalScrollSearchRequest request = new InternalScrollSearchRequest();
                listener.validateReaderContext(readerContext, request);
                assertThat(threadContext.getTransient(AuthorizationServiceField.INDICES_PERMISSIONS_KEY), is(indicesAccessControl));
                verifyNoMoreInteractions(auditTrail);
            }

            // the user that authenticated for the run as request
            try (StoredContext ignore = threadContext.newStoredContext(false)) {
                final String nodeName = randomBoolean() ? "node" : randomAlphaOfLengthBetween(1, 8);
                final String realmName = randomBoolean() ? "realm" : randomAlphaOfLengthBetween(1, 16);
                final String type = randomAlphaOfLengthBetween(5, 16);
                Authentication authentication = new Authentication(
                    new User("authenticated", "runas"),
                    new RealmRef(realmName, type, nodeName),
                    null
                );
                authentication.writeToContext(threadContext);
                threadContext.putTransient(ORIGINATING_ACTION_KEY, "action");
                threadContext.putTransient(
                    AUTHORIZATION_INFO_KEY,
                    (AuthorizationInfo) () -> Collections.singletonMap(PRINCIPAL_ROLES_FIELD_NAME, authentication.getUser().roles())
                );
                final InternalScrollSearchRequest request = new InternalScrollSearchRequest();
                SearchContextMissingException expected = expectThrows(
                    SearchContextMissingException.class,
                    () -> listener.validateReaderContext(readerContext, request)
                );
                assertEquals(readerContext.id(), expected.contextId());
                assertThat(threadContext.getTransient(AuthorizationServiceField.INDICES_PERMISSIONS_KEY), nullValue());
                verify(auditTrail).accessDenied(
                    eq(null),
                    eq(authentication),
                    eq("action"),
                    eq(request),
                    authzInfoRoles(authentication.getUser().roles())
                );
            }
        }
    }

    public void testValidateResourceAccessCheck() throws Exception {
        final ShardSearchRequest shardSearchRequest = mock(ShardSearchRequest.class);
        when(shardSearchRequest.scroll()).thenReturn(new Scroll(TimeValue.timeValueMinutes(between(1, 10))));
        final ShardSearchContextId shardSearchContextId = new ShardSearchContextId(UUIDs.randomBase64UUID(), randomLong());
        try (
            LegacyReaderContext readerContext = new LegacyReaderContext(
                shardSearchContextId,
                indexService,
                shard,
                shard.acquireSearcherSupplier(),
                shardSearchRequest,
                Long.MAX_VALUE
            )
        ) {
            readerContext.putInContext(AuthorizationServiceField.INDICES_PERMISSIONS_KEY, mock(IndicesAccessControl.class));
            final MockLicenseState licenseState = mock(MockLicenseState.class);
            when(licenseState.isAllowed(Security.AUDITING_FEATURE)).thenReturn(true);
            final SecurityContext securityContext = new SecurityContext(Settings.EMPTY, new ThreadContext(Settings.EMPTY));
            final AuditTrail auditTrail = mock(AuditTrail.class);
            final AuditTrailService auditTrailService = new AuditTrailService(List.of(auditTrail), licenseState);

            final SecuritySearchOperationListener listener = new SecuritySearchOperationListener(securityContext, auditTrailService);
            final TransportRequest request = mock(TransportRequest.class);

            // same user
            try (ThreadContext.StoredContext ignore = securityContext.getThreadContext().stashContext()) {
                readerContext.putInContext(
                    AuthenticationField.AUTHENTICATION_KEY,
                    new Authentication(new User("test", "role"), new RealmRef("realm", "file", "node"), null)
                );
                new Authentication(new User("test", "role"), new Authentication.RealmRef("realm", "file", "node"), null).writeToContext(
                    securityContext.getThreadContext()
                );
                listener.validateReaderContext(readerContext, request);
                verifyNoMoreInteractions(auditTrail);
            }

            // only current user being run as
            try (ThreadContext.StoredContext ignore = securityContext.getThreadContext().stashContext()) {
                readerContext.putInContext(
                    AuthenticationField.AUTHENTICATION_KEY,
                    new Authentication(new User("test", "role"), new RealmRef("realm", "file", "node"), null)
                );
                new Authentication(
                    new User(new User("test", "role"), new User("authenticated", "runas")),
                    new RealmRef(randomAlphaOfLengthBetween(1, 16), randomAlphaOfLength(5), randomAlphaOfLength(5)),
                    new RealmRef("realm", "file", "node")
                ).writeToContext(securityContext.getThreadContext());
                listener.validateReaderContext(readerContext, request);
                verifyNoMoreInteractions(auditTrail);
            }

            // only original user being run as
            try (ThreadContext.StoredContext ignore = securityContext.getThreadContext().stashContext()) {
                readerContext.putInContext(
                    AuthenticationField.AUTHENTICATION_KEY,
                    new Authentication(
                        new User(new User("test", "role"), new User("authenticated", "runas")),
                        new RealmRef(randomAlphaOfLengthBetween(1, 16), randomAlphaOfLength(5), "node"),
                        new RealmRef("realm2", "file", "node")
                    )
                );
                new Authentication(new User("test", "role"), new Authentication.RealmRef("realm", "file", "node"), null).writeToContext(
                    securityContext.getThreadContext()
                );
                listener.validateReaderContext(readerContext, request);
                verifyNoMoreInteractions(auditTrail);
            }

            // both user are run as
            try (ThreadContext.StoredContext ignore = securityContext.getThreadContext().stashContext()) {
                readerContext.putInContext(
                    AuthenticationField.AUTHENTICATION_KEY,
                    new Authentication(
                        new User(new User("test", "role"), new User("authenticated", "runas")),
                        new RealmRef(randomAlphaOfLengthBetween(1, 16), randomAlphaOfLength(5), "node"),
                        new RealmRef("realm", "file", "node")
                    )
                );
                new Authentication(
                    new User(new User("test", "role"), new User("authenticated", "runas")),
                    new RealmRef(randomAlphaOfLengthBetween(1, 16), randomAlphaOfLength(5), "node"),
                    new RealmRef("realm2", "file", "node")
                ).writeToContext(securityContext.getThreadContext());
                listener.validateReaderContext(readerContext, request);
                verifyNoMoreInteractions(auditTrail);
            }

            // current authenticated by different realm type
            try (ThreadContext.StoredContext ignore = securityContext.getThreadContext().stashContext()) {
                if (randomBoolean()) {
                    readerContext.putInContext(
                        AuthenticationField.AUTHENTICATION_KEY,
                        new Authentication(
                            new User(new User("test", "role"), new User("authenticated", "runas")),
                            new RealmRef(randomAlphaOfLengthBetween(1, 16), randomAlphaOfLength(5), "node"),
                            new RealmRef("realm", "file", "node")
                        )
                    );
                } else {
                    readerContext.putInContext(
                        AuthenticationField.AUTHENTICATION_KEY,
                        new Authentication(new User("test", "role"), new RealmRef("realm", "file", "node"), null)
                    );
                }
                final String differentRealmType = randomAlphaOfLength(5); // different from "file" which has length 4
                Authentication currentAuthn = randomBoolean()
                    ? new Authentication(new User("test", "role"), new Authentication.RealmRef("realm", differentRealmType, "node"), null)
                    : new Authentication(
                        new User(new User("test", "role"), new User("authenticated", "runas")),
                        new RealmRef(randomAlphaOfLengthBetween(1, 16), randomAlphaOfLength(5), "node"),
                        new RealmRef("realm", differentRealmType, "node")
                    );
                currentAuthn.writeToContext(securityContext.getThreadContext());
                SearchContextMissingException e = expectThrows(
                    SearchContextMissingException.class,
                    () -> listener.validateReaderContext(readerContext, request)
                );
                assertThat(e.contextId(), is(shardSearchContextId));
                verify(auditTrail).accessDenied(isNull(), eq(currentAuthn), isNull(), eq(request), isNull());
            }

            // current authenticated by different user
            try (ThreadContext.StoredContext ignore = securityContext.getThreadContext().stashContext()) {
                if (randomBoolean()) {
                    readerContext.putInContext(
                        AuthenticationField.AUTHENTICATION_KEY,
                        new Authentication(
                            new User(new User("test", "role"), new User("authenticated", "runas")),
                            new RealmRef(randomAlphaOfLengthBetween(1, 16), randomAlphaOfLength(5), "node"),
                            new RealmRef("realm", "file", "node")
                        )
                    );
                } else {
                    readerContext.putInContext(
                        AuthenticationField.AUTHENTICATION_KEY,
                        new Authentication(new User("test", "role"), new RealmRef("realm", "file", "node"), null)
                    );
                }
                Authentication currentAuthn = randomBoolean()
                    ? new Authentication(
                        new User(randomAlphaOfLength(5), "role"),
                        new Authentication.RealmRef("realm", "file", "node"),
                        null
                    )
                    : new Authentication(
                        new User(new User(randomAlphaOfLength(5), "role"), new User("authenticated", "runas")),
                        new RealmRef(randomAlphaOfLengthBetween(1, 16), "file", "node"),
                        new RealmRef("realm", "file", "node")
                    );
                currentAuthn.writeToContext(securityContext.getThreadContext());
                SearchContextMissingException e = expectThrows(
                    SearchContextMissingException.class,
                    () -> listener.validateReaderContext(readerContext, request)
                );
                assertThat(e.contextId(), is(shardSearchContextId));
                verify(auditTrail).accessDenied(isNull(), eq(currentAuthn), isNull(), eq(request), isNull());
            }
        }

    }
}
