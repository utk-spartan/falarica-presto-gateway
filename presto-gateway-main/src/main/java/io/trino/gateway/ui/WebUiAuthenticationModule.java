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
package io.trino.gateway.ui;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.server.security.Authenticator;
import io.trino.server.security.CertificateAuthenticator;
import io.trino.server.security.CertificateConfig;
import io.trino.server.security.KerberosAuthenticator;
import io.trino.server.security.KerberosConfig;
import io.trino.server.security.SecurityConfig;
import io.trino.server.security.jwt.JwtAuthenticator;
import io.trino.server.security.jwt.JwtAuthenticatorConfig;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class WebUiAuthenticationModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(WebUiAuthenticationConfig.class);

        installWebUiAuthenticator("form", new FormUiAuthenticatorModule());
        installWebUiAuthenticator("fixed", new FixedUiAuthenticatorModule());

        installWebUiAuthenticator("certificate", CertificateAuthenticator.class, CertificateConfig.class);
        installWebUiAuthenticator("kerberos", KerberosAuthenticator.class, KerberosConfig.class);
        installWebUiAuthenticator("jwt", JwtAuthenticator.class, JwtAuthenticatorConfig.class);
    }

    private void installWebUiAuthenticator(String type, Module module)
    {
        install(webUiAuthenticator(type, module));
    }

    private void installWebUiAuthenticator(String name, Class<? extends Authenticator> authenticator, Class<?> config)
    {
        install(webUiAuthenticator(name, authenticator, binder -> configBinder(binder).bindConfig(config)));
    }

    public static Module webUiAuthenticator(String type, Module module)
    {
        return new ConditionalWebUiAuthenticationModule(type, module);
    }

    public static Module webUiAuthenticator(String name, Class<? extends Authenticator> clazz, Module module)
    {
        checkArgument(name.toLowerCase(ENGLISH).equals(name), "name is not lower case: %s", name);
        Module authModule = binder -> {
            binder.install(new FormUiAuthenticatorModule());
            newOptionalBinder(binder, Key.get(Authenticator.class, ForWebUi.class)).setBinding().to(clazz).in(SINGLETON);
        };
        return webUiAuthenticator(name, Modules.combine(module, authModule));
    }

    private static class ConditionalWebUiAuthenticationModule
            extends AbstractConfigurationAwareModule
    {
        private final String type;
        private final Module module;

        public ConditionalWebUiAuthenticationModule(String type, Module module)
        {
            this.type = requireNonNull(type, "type is null");
            this.module = requireNonNull(module, "module is null");
        }

        @Override
        protected void setup(Binder binder)
        {
            if (type.equals(getAuthenticationType())) {
                install(module);
            }
        }

        private String getAuthenticationType()
        {
            String authentication = buildConfigObject(WebUiAuthenticationConfig.class).getAuthentication();
            if (authentication != null) {
                return authentication;
            }

            // no authenticator explicitly set for the web ui, so choose a default:
            // If there is a password authenticator, use that.
            List<String> authenticationTypes = buildConfigObject(SecurityConfig.class).getAuthenticationTypes().stream()
                    .map(type -> type.toLowerCase(ENGLISH))
                    .collect(toImmutableList());
            if (authenticationTypes.contains("password")) {
                return "form";
            }
            // otherwise use the first authenticator, or if there are no authenticators
            // configured, use form for the UI since it handles this case
            return authenticationTypes.stream().findFirst().orElse("form");
        }
    }
}
