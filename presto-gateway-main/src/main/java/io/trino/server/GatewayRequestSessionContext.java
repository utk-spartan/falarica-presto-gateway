package io.trino.server;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.Session;
import io.trino.client.ProtocolHeaders;
import io.trino.security.AccessControl;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.GroupProvider;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SelectedRole;
import io.trino.spi.session.ResourceEstimates;
import io.trino.sql.parser.ParsingException;
import io.trino.sql.parser.ParsingOptions;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.AliasedRelation;
import io.trino.sql.tree.DefaultTraversalVisitor;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.Relation;
import io.trino.sql.tree.Statement;
import io.trino.sql.tree.Table;
import io.trino.transaction.TransactionId;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.net.HttpHeaders.USER_AGENT;
import static io.trino.client.ProtocolHeaders.TRINO_HEADERS;
import static io.trino.spi.StandardErrorCode.MISSING_CATALOG_NAME;
import static io.trino.spi.StandardErrorCode.MISSING_SCHEMA_NAME;
import static io.trino.sql.analyzer.SemanticExceptions.semanticException;
import static io.trino.sql.parser.ParsingOptions.DecimalLiteralTreatment.AS_DOUBLE;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class GatewayRequestSessionContext
        implements SessionContext
{
    private static final String TRINO_CATALOG = TRINO_HEADERS.requestCatalog();
    private static final String TRINO_CLIENT_CAPABILITIES = TRINO_HEADERS.requestClientCapabilities();
    private static final String TRINO_CLIENT_INFO = TRINO_HEADERS.requestClientInfo();
    private static final String TRINO_CLIENT_TAGS = TRINO_HEADERS.requestClientTags();
    private static final String TRINO_EXTRA_CREDENTIAL = TRINO_HEADERS.requestExtraCredential();
    private static final String TRINO_LANGUAGE = TRINO_HEADERS.requestLanguage();
    private static final String TRINO_PATH = TRINO_HEADERS.requestPath();
    private static final String TRINO_PREPARED_STATEMENT = TRINO_HEADERS.requestPreparedStatement();
    private static final String TRINO_RESOURCE_ESTIMATE = TRINO_HEADERS.requestResourceEstimate();
    private static final String TRINO_ROLE = TRINO_HEADERS.requestRole();
    private static final String TRINO_SCHEMA = TRINO_HEADERS.requestSchema();
    private static final String TRINO_SESSION = TRINO_HEADERS.requestSession();
    private static final String TRINO_SOURCE = TRINO_HEADERS.requestSource();
    private static final String TRINO_TIME_ZONE = TRINO_HEADERS.requestTimeZone();
    private static final String TRINO_TRACE_TOKEN = TRINO_HEADERS.requestTraceToken();
    private static final String TRINO_TRANSACTION_ID = TRINO_HEADERS.requestTransactionId();
    private static final String TRINO_USER = TRINO_HEADERS.requestUser();
    private String sql;
    SqlParser parser = new SqlParser();
    ParsingOptions options = new ParsingOptions(ParsingOptions.DecimalLiteralTreatment.AS_DOUBLE);

    private final String catalog;
    private final String schema;
    private final String path;

    private final Optional<Identity> authenticatedIdentity;
    private final Identity identity;

    private final String source;
    private final Optional<String> traceToken;
    private final String userAgent;
    private final String remoteUserAddress;
    private final String timeZoneId;
    private final String language;
    private final Set<String> clientTags;
    private final Set<String> clientCapabilities;
    private final ResourceEstimates resourceEstimates;
    private Set<CatalogSchemaTableName> catalogtables;

    private final Map<String, String> systemProperties;
    private final Map<String, Map<String, String>> catalogSessionProperties;

    private final Map<String, String> preparedStatements;

    private final Optional<TransactionId> transactionId;
    private final boolean clientTransactionSupport;
    private final String clientInfo;

    private static final Logger log = Logger.get(HttpRequestSessionContext.class);

    private static final Splitter DOT_SPLITTER = Splitter.on('.');
    public static final String AUTHENTICATED_IDENTITY = "presto.authenticated-identity";

    public GatewayRequestSessionContext(MultivaluedMap<String, String> headers, String remoteAddress, Optional<Identity> authenticatedIdentity,
            GroupProvider groupProvider, String sql)
            throws WebApplicationException
    {
        catalog = trimEmptyToNull(headers.getFirst(TRINO_CATALOG));

        schema = trimEmptyToNull(headers.getFirst(TRINO_SCHEMA));
        path = trimEmptyToNull(headers.getFirst(TRINO_PATH));
        assertRequest((catalog != null) || (schema == null), "Schema is set but catalog is not");

        this.authenticatedIdentity = requireNonNull(authenticatedIdentity, "authenticatedIdentity is null");
        identity = buildSessionIdentity(authenticatedIdentity, headers, groupProvider);

        source = headers.getFirst(TRINO_SOURCE);
        traceToken = Optional.ofNullable(trimEmptyToNull(headers.getFirst(TRINO_TRACE_TOKEN)));
        userAgent = headers.getFirst(USER_AGENT);
        remoteUserAddress = remoteAddress;
        timeZoneId = headers.getFirst(TRINO_TIME_ZONE);
        language = headers.getFirst(TRINO_LANGUAGE);
        clientInfo = headers.getFirst(TRINO_CLIENT_INFO);
        clientTags = parseClientTags(headers);
        clientCapabilities = parseClientCapabilities(headers);
        resourceEstimates = parseResourceEstimate(headers);
        this.sql = sql;
        // parse session properties
        ImmutableMap.Builder<String, String> systemProperties = ImmutableMap.builder();
        Map<String, Map<String, String>> catalogSessionProperties = new HashMap<>();
        for (Map.Entry<String, String> entry : parseSessionHeaders(headers).entrySet()) {
            String fullPropertyName = entry.getKey();
            String propertyValue = entry.getValue();
            List<String> nameParts = DOT_SPLITTER.splitToList(fullPropertyName);
            if (nameParts.size() == 1) {
                String propertyName = nameParts.get(0);

                assertRequest(!propertyName.isEmpty(), "Invalid %s header", TRINO_SESSION);

                // catalog session properties cannot be validated until the transaction has stated, so we delay system property validation also
                systemProperties.put(propertyName, propertyValue);
            }
            else if (nameParts.size() == 2) {
                String catalogName = nameParts.get(0);
                String propertyName = nameParts.get(1);

                assertRequest(!catalogName.isEmpty(), "Invalid %s header", TRINO_SESSION);
                assertRequest(!propertyName.isEmpty(), "Invalid %s header", TRINO_SESSION);

                // catalog session properties cannot be validated until the transaction has stated
                catalogSessionProperties.computeIfAbsent(catalogName, id -> new HashMap<>()).put(propertyName, propertyValue);
            }
            else {
                throw badRequest(format("Invalid %s header", TRINO_SESSION));
            }
        }
        this.systemProperties = systemProperties.build();
        this.catalogSessionProperties = catalogSessionProperties.entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, entry -> ImmutableMap.copyOf(entry.getValue())));

        preparedStatements = parsePreparedStatementsHeaders(headers);

        String transactionIdHeader = headers.getFirst(TRINO_TRANSACTION_ID);
        clientTransactionSupport = transactionIdHeader != null;
        transactionId = parseTransactionId(transactionIdHeader);
    }

    public synchronized Set<CatalogSchemaTableName> getQueryCatalogTables()
    {
        if (this.catalogtables == null) {
            this.catalogtables = parseQueryForTables();
        }
        return this.catalogtables;
    }

    public Set<String> getQueryCatalogs()
    {
        Set<String> catalogs = new HashSet<>();
        for (CatalogSchemaTableName c : this.getQueryCatalogTables()) {
            catalogs.add(c.getCatalogName());
        }
        return catalogs;
    }

    private Set<CatalogSchemaTableName> parseQueryForTables()
    {
        Set<CatalogSchemaTableName> catalogTables = new HashSet<>();
        TableVisitor tableVisitor = new TableVisitor(catalogTables);
        if (sql.isEmpty()) {
            return catalogTables;
        }
        Statement wrappedStatement = parser.createStatement(sql, options);
        wrappedStatement.accept(tableVisitor, null);
        return catalogTables;
    }

    private class TableVisitor<Scope>
            extends DefaultTraversalVisitor<Scope>
    {
        public TableVisitor(Set<CatalogSchemaTableName> catalogTables)
        {
            this.catalogTables = catalogTables;
        }

        private final Set<CatalogSchemaTableName> catalogTables;

        @Override
        protected Void visitNode(Node node, Scope context)
        {
            if (node instanceof AliasedRelation) {
                Relation rel = ((AliasedRelation) node).getRelation();
                if (rel instanceof Table) {
                    String name = ((Table) rel).getName().toString();
                    catalogTables.add(getCatalogTableName(name, node));
                }
            }
            else if (node instanceof Table) {
                String name = ((Table) node).getName().toString();
                catalogTables.add(getCatalogTableName(name, node));
            }
            return null;
        }

        private CatalogSchemaTableName getCatalogTableName(String name, Node node)
        {
            String[] tableParts = ((String) name).split("\\.");
            String catalogName = getCatalog();
            String schemaName = getSchema();
            String tableName;
            if (tableParts.length == 3) {
                catalogName = tableParts[0];
                schemaName = tableParts[1];
            }
            else if (tableParts.length == 2) {
                schemaName = tableParts[0];
            }
            tableName = tableParts[tableParts.length - 1];
            if (catalogName == null) {
                throw semanticException(MISSING_CATALOG_NAME, node, "Catalog must be specified when session catalog is not set");
            }
            if (schemaName == null) {
                throw semanticException(MISSING_SCHEMA_NAME, node, "Schema must be specified when session schema is not set");
            }
            return new CatalogSchemaTableName(catalogName, schemaName, tableName);
        }
    }

    public static Identity extractAuthorizedIdentity(HttpServletRequest servletRequest, HttpHeaders httpHeaders, AccessControl accessControl, GroupProvider groupProvider)
    {
        return extractAuthorizedIdentity(
                Optional.ofNullable((Identity) servletRequest.getAttribute(AUTHENTICATED_IDENTITY)),
                httpHeaders.getRequestHeaders(),
                accessControl,
                groupProvider);
    }

    public static Identity extractAuthorizedIdentity(Optional<Identity> optionalAuthenticatedIdentity, MultivaluedMap<String, String> headers, AccessControl accessControl, GroupProvider groupProvider)
            throws AccessDeniedException
    {
        Identity identity = buildSessionIdentity(optionalAuthenticatedIdentity, headers, groupProvider);

        accessControl.checkCanSetUser(identity.getPrincipal(), identity.getUser());

        // authenticated may not present for HTTP or if authentication is not setup
        optionalAuthenticatedIdentity.ifPresent(authenticatedIdentity -> {
            // only check impersonation if authenticated user is not the same as the explicitly set user
            if (!authenticatedIdentity.getUser().equals(identity.getUser())) {
                accessControl.checkCanImpersonateUser(authenticatedIdentity, identity.getUser());
            }
        });

        return identity;
    }

    private static Identity buildSessionIdentity(Optional<Identity> authenticatedIdentity, MultivaluedMap<String, String> headers, GroupProvider groupProvider)
    {
        String prestoUser = trimEmptyToNull(headers.getFirst(TRINO_USER));
        String user = prestoUser != null ? prestoUser : authenticatedIdentity.map(Identity::getUser).orElse(null);
        assertRequest(user != null, "User must be set");
        return authenticatedIdentity
                .map(identity -> Identity.from(identity).withUser(user))
                .orElseGet(() -> Identity.forUser(user))
                .withAdditionalRoles(parseRoleHeaders(headers))
                .withAdditionalExtraCredentials(parseExtraCredentials(headers))
                .withAdditionalGroups(groupProvider.getGroups(user))
                .build();
    }

    @Override
    public ProtocolHeaders getProtocolHeaders()
    {
        return TRINO_HEADERS;
    }

    @Override
    public Optional<Identity> getAuthenticatedIdentity()
    {
        return authenticatedIdentity;
    }

    @Override
    public Identity getIdentity()
    {
        return identity;
    }

    @Override
    public String getCatalog()
    {
        return catalog;
    }

    @Override
    public String getSchema()
    {
        return schema;
    }

    @Override
    public String getPath()
    {
        return path;
    }

    @Override
    public String getSource()
    {
        return source;
    }

    @Override
    public String getRemoteUserAddress()
    {
        return remoteUserAddress;
    }

    @Override
    public String getUserAgent()
    {
        return userAgent;
    }

    @Override
    public String getClientInfo()
    {
        return clientInfo;
    }

    @Override
    public Set<String> getClientTags()
    {
        return clientTags;
    }

    @Override
    public Set<String> getClientCapabilities()
    {
        return clientCapabilities;
    }

    @Override
    public ResourceEstimates getResourceEstimates()
    {
        return resourceEstimates;
    }

    @Override
    public String getTimeZoneId()
    {
        return timeZoneId;
    }

    @Override
    public String getLanguage()
    {
        return language;
    }

    @Override
    public Map<String, String> getSystemProperties()
    {
        return systemProperties;
    }

    @Override
    public Map<String, Map<String, String>> getCatalogSessionProperties()
    {
        return catalogSessionProperties;
    }

    @Override
    public Map<String, String> getPreparedStatements()
    {
        return preparedStatements;
    }

    @Override
    public Optional<TransactionId> getTransactionId()
    {
        return transactionId;
    }

    @Override
    public boolean supportClientTransaction()
    {
        return clientTransactionSupport;
    }

    @Override
    public Optional<String> getTraceToken()
    {
        return traceToken;
    }

    private static List<String> splitHttpHeader(MultivaluedMap<String, String> headers, String name)
    {
        List<String> values = firstNonNull(headers.get(name), ImmutableList.of());
        Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();
        return values.stream()
                .map(splitter::splitToList)
                .flatMap(Collection::stream)
                .collect(toImmutableList());
    }

    private static Map<String, String> parseSessionHeaders(MultivaluedMap<String, String> headers)
    {
        return parseProperty(headers, TRINO_SESSION);
    }

    private static Map<String, SelectedRole> parseRoleHeaders(MultivaluedMap<String, String> headers)
    {
        ImmutableMap.Builder<String, SelectedRole> roles = ImmutableMap.builder();
        parseProperty(headers, TRINO_ROLE).forEach((key, value) -> {
            SelectedRole role;
            try {
                role = SelectedRole.valueOf(value);
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Invalid %s header", TRINO_ROLE));
            }
            roles.put(key, role);
        });
        return roles.build();
    }

    private static Map<String, String> parseExtraCredentials(MultivaluedMap<String, String> headers)
    {
        return parseProperty(headers, TRINO_EXTRA_CREDENTIAL);
    }

    private static Map<String, String> parseProperty(MultivaluedMap<String, String> headers, String headerName)
    {
        Map<String, String> properties = new HashMap<>();
        for (String header : splitHttpHeader(headers, headerName)) {
            List<String> nameValue = Splitter.on('=').trimResults().splitToList(header);
            assertRequest(nameValue.size() == 2, "Invalid %s header", headerName);
            try {
                properties.put(nameValue.get(0), urlDecode(nameValue.get(1)));
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Invalid %s header: %s", headerName, e));
            }
        }
        return properties;
    }

    private static Set<String> parseClientTags(MultivaluedMap<String, String> headers)
    {
        Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();
        return ImmutableSet.copyOf(splitter.split(nullToEmpty(headers.getFirst(TRINO_CLIENT_TAGS))));
    }

    private static Set<String> parseClientCapabilities(MultivaluedMap<String, String> headers)
    {
        Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();
        return ImmutableSet.copyOf(splitter.split(nullToEmpty(headers.getFirst(TRINO_CLIENT_CAPABILITIES))));
    }

    private static ResourceEstimates parseResourceEstimate(MultivaluedMap<String, String> headers)
    {
        Session.ResourceEstimateBuilder builder = new Session.ResourceEstimateBuilder();
        parseProperty(headers, TRINO_RESOURCE_ESTIMATE).forEach((name, value) -> {
            try {
                switch (name.toUpperCase()) {
                    case ResourceEstimates.EXECUTION_TIME:
                        builder.setExecutionTime(Duration.valueOf(value));
                        break;
                    case ResourceEstimates.CPU_TIME:
                        builder.setCpuTime(Duration.valueOf(value));
                        break;
                    case ResourceEstimates.PEAK_MEMORY:
                        builder.setPeakMemory(DataSize.valueOf(value));
                        break;
                    default:
                        throw badRequest(format("Unsupported resource name %s", name));
                }
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Unsupported format for resource estimate '%s': %s", value, e));
            }
        });

        return builder.build();
    }

    private static void assertRequest(boolean expression, String format, Object... args)
    {
        if (!expression) {
            throw badRequest(format(format, args));
        }
    }

    private static Map<String, String> parsePreparedStatementsHeaders(MultivaluedMap<String, String> headers)
    {
        ImmutableMap.Builder<String, String> preparedStatements = ImmutableMap.builder();
        parseProperty(headers, TRINO_PREPARED_STATEMENT).forEach((key, sqlString) -> {
            String statementName;
            try {
                statementName = urlDecode(key);
            }
            catch (IllegalArgumentException e) {
                throw badRequest(format("Invalid %s header: %s", TRINO_PREPARED_STATEMENT, e.getMessage()));
            }

            // Validate statement
            SqlParser sqlParser = new SqlParser();
            try {
                sqlParser.createStatement(sqlString, new ParsingOptions(AS_DOUBLE /* anything */));
            }
            catch (ParsingException e) {
                throw badRequest(format("Invalid %s header: %s", TRINO_PREPARED_STATEMENT, e.getMessage()));
            }

            preparedStatements.put(statementName, sqlString);
        });

        return preparedStatements.build();
    }

    private static Optional<TransactionId> parseTransactionId(String transactionId)
    {
        transactionId = trimEmptyToNull(transactionId);
        if (transactionId == null || transactionId.equalsIgnoreCase("none")) {
            return Optional.empty();
        }
        try {
            return Optional.of(TransactionId.valueOf(transactionId));
        }
        catch (Exception e) {
            throw badRequest(e.getMessage());
        }
    }

    private static WebApplicationException badRequest(String message)
    {
        throw new WebApplicationException(message, Response
                .status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN)
                .entity(message)
                .build());
    }

    private static String trimEmptyToNull(String value)
    {
        return emptyToNull(nullToEmpty(value).trim());
    }

    private static String urlDecode(String value)
    {
        try {
            return URLDecoder.decode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
