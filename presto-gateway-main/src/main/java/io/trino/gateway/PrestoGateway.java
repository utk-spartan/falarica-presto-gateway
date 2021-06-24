/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway;

import com.google.common.collect.ImmutableList;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.event.client.EventModule;
import io.airlift.http.server.HttpServerModule;
import io.airlift.jaxrs.JaxrsModule;
import io.airlift.jmx.JmxModule;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonModule;
import io.airlift.log.LogJmxModule;
import io.airlift.log.Logger;
import io.airlift.node.NodeModule;
import io.airlift.tracetoken.TraceTokenModule;
import io.trino.eventlistener.EventListenerModule;
import io.trino.execution.QueryInfo;
import io.trino.execution.resourcegroups.ResourceGroupManager;
import io.trino.execution.warnings.WarningCollectorModule;
import io.trino.metadata.StaticCatalogStore;
import io.trino.security.GroupProviderManager;
import io.trino.security.SteerDAccessControlManager;
import io.trino.security.SteerDAccessControlModule;
import io.trino.server.security.GatewaySecurityModule;
import io.trino.server.security.PasswordAuthenticatorManager;
import org.weakref.jmx.guice.MBeanModule;

public final class PrestoGateway
{
    private PrestoGateway() {}

    public static Injector start(Module... extraModules)
    {
        Injector injector = null;
        Bootstrap app = new Bootstrap(ImmutableList.<Module>builder()
                .add(new NodeModule())
                .add(new HttpServerModule())
                .add(new JsonModule())
                .add(new JaxrsModule())
                .add(new MBeanModule())
                .add(new JmxModule())
                .add(new LogJmxModule())
                .add(new TraceTokenModule())
                .add(new EventModule())
                .add(new GatewaySecurityModule())
                .add(new SteerDAccessControlModule())
                .add(new WarningCollectorModule())
                .add(new GatewayModule())
                .add(new EventListenerModule())
                .add(extraModules)
                .build());

        Logger log = Logger.get(PrestoGateway.class);
        try {
            injector = app.strictConfig().initialize();
            injector.getInstance(ResourceGroupManager.class).loadConfigurationManager();
            injector.getInstance(SteerDAccessControlManager.class).loadSystemAccessControl();
            injector.getInstance(PasswordAuthenticatorManager.class).loadPasswordAuthenticator();
            injector.getInstance(GroupProviderManager.class).loadConfiguredGroupProvider();
            injector.getInstance(StaticCatalogStore.class).loadCatalogs();

            JsonCodec<QueryInfo> codec = injector.getInstance(Key.get(new TypeLiteral<JsonCodec<QueryInfo>>() {}));
            injector.getInstance(MultiClusterManager.class).setQueryInfoCodec(codec);
            log.info("======== SERVER STARTED ========");
        }
        catch (Throwable t) {
            log.error(t);
            System.exit(1);
        }
        return injector;
    }

    public static void main(String[] args)
    {
        start();
    }
}
